#include <oboe/Oboe.h>
#include <opus/opus.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace {
constexpr int kOpusSampleRate = 48000;
constexpr int kOpusChannels = 2;
constexpr int kOpusMaxFrameSamples = 5760; // 120ms @ 48kHz
constexpr int64_t kMaxEndPaddingFrames = 960;
constexpr size_t kOpusInitialPrefillFrames = kOpusSampleRate * 2; // 2.0s synchronous prefill
constexpr size_t kOpusBackgroundChunkFrames = kOpusSampleRate / 2; // publish every 0.5s

uint16_t readLe16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

int64_t monotonicNowMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

class OggOpusStream {
    std::vector<uint8_t> data;
    size_t pos = 0;
    uint8_t segIdx = 0;
    uint8_t numSegments = 0;
    size_t segTableStart = 0;
    size_t bodyPos = 0;
    bool inPage = false;

    OpusDecoder* decoder = nullptr;
    int skipRemaining = 0;
    float outputGain = 1.0f;
    int packetIndex = 0;
    std::vector<uint8_t> packet;
    std::array<float, kOpusMaxFrameSamples * kOpusChannels> frameBuf{};

    // Reads the next complete Ogg packet into `packet`. Returns false on EOF or format error.
    bool nextPacket() {
        packet.clear();
        while (true) {
            if (!inPage) {
                if (pos >= data.size()) {
                    eof = true;
                    if (!packet.empty()) error = true;
                    return false;
                }
                if (pos + 27 > data.size() || std::memcmp(data.data() + pos, "OggS", 4) != 0 || data[pos + 4] != 0) {
                    error = true;
                    return false;
                }
                numSegments = data[pos + 26];
                segTableStart = pos + 27;
                bodyPos = segTableStart + numSegments;
                if (bodyPos > data.size()) {
                    error = true;
                    return false;
                }
                segIdx = 0;
                inPage = true;
            }
            while (segIdx < numSegments) {
                const uint8_t segLen = data[segTableStart + segIdx];
                segIdx++;
                if (bodyPos + segLen > data.size()) {
                    error = true;
                    return false;
                }
                if (segLen > 0) {
                    packet.insert(packet.end(), data.data() + bodyPos, data.data() + bodyPos + segLen);
                    bodyPos += segLen;
                }
                if (segLen < 255) {
                    if (segIdx == numSegments) {
                        pos = bodyPos;
                        inPage = false;
                    }
                    if (!packet.empty()) return true;
                }
            }
            pos = bodyPos;
            inPage = false;
        }
    }

public:
    size_t targetFrames = 0;
    size_t maxAllowedFrames = 0;
    size_t decodedTotalFrames = 0;
    size_t writtenFrames = 0;
    bool eof = false;
    bool error = false;

    ~OggOpusStream() {
        if (decoder) opus_decoder_destroy(decoder);
    }

    bool open(const char* path, int64_t expectedFrames) {
        if (!path || expectedFrames <= 0) return false;
        FILE* f = std::fopen(path, "rb");
        if (!f) return false;
        if (std::fseek(f, 0, SEEK_END) != 0) { std::fclose(f); return false; }
        const long fileLen = std::ftell(f);
        if (fileLen <= 27 || fileLen > 64L * 1024L * 1024L) { std::fclose(f); return false; }
        std::rewind(f);
        data.resize(static_cast<size_t>(fileLen));
        const size_t readCount = std::fread(data.data(), 1, data.size(), f);
        std::fclose(f);
        if (readCount != data.size()) return false;

        targetFrames = static_cast<size_t>(expectedFrames);
        maxAllowedFrames = static_cast<size_t>(expectedFrames + kMaxEndPaddingFrames);
        packet.reserve(4096);

        // Packet 0: OpusHead
        if (!nextPacket() || packet.size() < 19 || std::memcmp(packet.data(), "OpusHead", 8) != 0) return false;
        const uint8_t channels = packet[9];
        const uint8_t mappingFamily = packet[18];
        if (channels != kOpusChannels || mappingFamily != 0) return false;
        skipRemaining = static_cast<int>(readLe16(packet.data() + 10));
        const int16_t gainQ8 = static_cast<int16_t>(readLe16(packet.data() + 16));
        outputGain = (gainQ8 == 0) ? 1.0f : static_cast<float>(std::pow(10.0, static_cast<double>(gainQ8) / (20.0 * 256.0)));
        int err = OPUS_OK;
        decoder = opus_decoder_create(kOpusSampleRate, kOpusChannels, &err);
        if (err != OPUS_OK || !decoder) return false;
        packetIndex = 1;

        // Packet 1: OpusTags
        if (!nextPacket() || packet.size() < 8 || std::memcmp(packet.data(), "OpusTags", 8) != 0) return false;
        packetIndex = 2;
        return true;
    }

    bool decodeUntil(float* dst, size_t stopAtFrames, const std::atomic<bool>* cancel) {
        const size_t goal = std::min(stopAtFrames, targetFrames);
        while (!eof && !error && writtenFrames < goal) {
            if (cancel && cancel->load(std::memory_order_acquire)) return false;
            if (!nextPacket()) break;
            const int decoded = opus_decode_float(
                decoder,
                packet.data(),
                static_cast<opus_int32>(packet.size()),
                frameBuf.data(),
                kOpusMaxFrameSamples,
                0
            );
            if (decoded < 0) {
                error = true;
                return false;
            }
            int offset = 0;
            if (skipRemaining > 0) {
                const int skipped = std::min(skipRemaining, decoded);
                skipRemaining -= skipped;
                offset = skipped;
            }
            const int keepFrames = decoded - offset;
            if (keepFrames > 0) {
                decodedTotalFrames += static_cast<size_t>(keepFrames);
                if (decodedTotalFrames > maxAllowedFrames) {
                    error = true;
                    return false;
                }
                const size_t copyFrames = std::min(static_cast<size_t>(keepFrames), targetFrames - writtenFrames);
                if (copyFrames > 0) {
                    const size_t copySamples = copyFrames * kOpusChannels;
                    const float* src = frameBuf.data() + static_cast<size_t>(offset) * kOpusChannels;
                    float* out = dst + writtenFrames * kOpusChannels;
                    if (outputGain == 1.0f) {
                        for (size_t i = 0; i < copySamples; ++i) {
                            const float v = src[i];
                            out[i] = std::isfinite(v) ? v : 0.0f;
                        }
                    } else {
                        for (size_t i = 0; i < copySamples; ++i) {
                            const float v = src[i] * outputGain;
                            out[i] = std::isfinite(v) ? v : 0.0f;
                        }
                    }
                    writtenFrames += copyFrames;
                }
            }
            packetIndex++;
        }
        return !error;
    }
};

struct Sample { int root=0, rate=48000; std::vector<float> pcm; };
struct Event { double start=0, end=0; int sample=0, mask=0; double step=1; float gain=0; };
struct Voice { int id=-1, sample=0; double cursor=0, step=1, end=0; float gain=0; int release=-1; };
struct Command { int kind=0, id=0; double elapsed=0; };

// One producer (Android main thread), one consumer (Oboe callback). Fixed-size storage.
class Queue {
    std::array<Command,256> items{};
    std::atomic<uint32_t> write{0}, read{0};
public:
    bool push(Command command) {
        const auto w=write.load(std::memory_order_relaxed), r=read.load(std::memory_order_acquire);
        if(w-r>=items.size())return false;
        items[w%items.size()]=command;write.store(w+1,std::memory_order_release);return true;
    }
    bool pop(Command& command) {
        const auto r=read.load(std::memory_order_relaxed);
        if(r==write.load(std::memory_order_acquire))return false;
        command=items[r%items.size()];read.store(r+1,std::memory_order_release);return true;
    }
    void clear() {read.store(write.load());}
};

class PianoEngine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
    std::shared_ptr<oboe::AudioStream> stream;
    std::vector<Sample> samples;
    std::vector<Event> events;
    // Complete stereo track; fixed allocation while active or background-decoding.
    std::unique_ptr<float[]> backingData;
    size_t backingTotalFrames=0;
    std::atomic<size_t> backingReadyFrames{0};
    int backingRate=44100;
    double backingSpeed=1, backingCursor=0;
    // Background progressive Opus decoder (never touched by Oboe callback).
    std::thread decoderThread;
    std::atomic<bool> decoderCancel{false};
    std::mutex decoderMutex;
    std::condition_variable decoderCv;
    bool decoderFinished=true;

    std::array<Voice,96> voices{};
    Queue commands;
    std::atomic<bool> active{false}, failed{false}, muted{false};
    std::atomic<float> lastOutputPeak{0};
    std::atomic<int64_t> visibleUs{0};
    std::atomic<int> countBeat{0};
    std::atomic<int> voiceCount{0};
    double frame=0, startFrame=0, endFrame=0, speed=1, smoothedLatencyFrames=-1.0;
    int64_t lastReportedUs=0;
    int rate=48000, modeMask=0, releaseFrames=11520;
    size_t next=0;
    int64_t countRemaining=0, beatFrames=0, clickRemaining=0;
    float master=.42f;

    void stopDecoder() {
        decoderCancel.store(true, std::memory_order_release);
        if (decoderThread.joinable()) {
            decoderThread.join();
        }
        decoderCancel.store(false, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(decoderMutex);
        decoderFinished = true;
    }
    void voice(int id,double elapsed=0) {
        if(id<0 || static_cast<size_t>(id)>=events.size())return;
        const auto& event=events[id];
        auto found=std::find_if(voices.begin(),voices.end(),[](const Voice& v){return v.id<0;});
        if(found==voices.end())found=std::max_element(voices.begin(),voices.end(),[](const Voice& a,const Voice& b){return a.cursor<b.cursor;});
        *found=Voice{id,event.sample,elapsed*event.step,event.step,frame+std::max(0.0,event.end-event.start-elapsed*speed),event.gain,-1};
    }
    void restoreAutomaticVoices() {
        for(size_t i=0;i<next;i++) {
            const auto& event=events[i];
            if(event.end>frame && !(event.mask&modeMask))voice(static_cast<int>(i),(frame-event.start)/speed);
        }
    }
public:
    bool open() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)->setUsage(oboe::Usage::Game)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)->setDataCallback(this)->setErrorCallback(this);
        if(builder.openStream(stream)!=oboe::Result::OK)return false;
        rate=stream->getSampleRate();releaseFrames=static_cast<int>(rate*.24);
        stream->setBufferSizeInFrames(stream->getFramesPerBurst()*2);
        return true;
    }
    void pause() {
        active.store(false,std::memory_order_release);
        if(stream && !failed.load())stream->pause(1000000000);
    }
    ~PianoEngine() {pause();if(stream)stream->close();stopDecoder();}
    void sample(int root,int sampleRate,std::vector<float> pcm) {
        pause();samples.push_back(Sample{root,sampleRate,std::move(pcm)});
    }
    bool score(const std::vector<double>& data) {
        pause();stopDecoder();events.clear();backingData.reset();backingTotalFrames=0;backingReadyFrames.store(0);commands.clear();
        if(samples.empty() || data.size()%5!=0)return false;
        for(size_t i=0;i<data.size();i+=5) {
            const int pitch=static_cast<int>(data[i+2]);
            auto best=std::min_element(samples.begin(),samples.end(),[pitch](const Sample&a,const Sample&b){return std::abs(a.root-pitch)<std::abs(b.root-pitch);});
            events.push_back(Event{data[i]*rate,(data[i]+data[i+1])*rate,static_cast<int>(best-samples.begin()),static_cast<int>(data[i+4]),
                std::pow(2.0,(pitch-best->root)/12.0)*best->rate/rate,static_cast<float>(data[i+3]/127.0)});
        }
        return std::is_sorted(events.begin(),events.end(),[](const Event&a,const Event&b){return a.start<b.start;});
    }
    bool recorded(int sampleRate,double multiplier,std::vector<float> pcm) {
        pause();stopDecoder();
        if(sampleRate<8000 || sampleRate>192000 || !(multiplier>0 && multiplier<=1) ||
            pcm.size()<4 || pcm.size()%2!=0 || !std::all_of(pcm.begin(),pcm.end(),[](float v){return std::isfinite(v);}))return false;
        auto buf = std::unique_ptr<float[]>(new float[pcm.size()]);
        std::memcpy(buf.get(), pcm.data(), pcm.size() * sizeof(float));
        backingData=std::move(buf);backingTotalFrames=pcm.size()/2;
        backingReadyFrames.store(backingTotalFrames,std::memory_order_release);
        backingRate=sampleRate;backingSpeed=multiplier;
        events.clear();commands.clear();for(auto& v:voices)v.id=-1;
        voiceCount.store(0);return true;
    }
    bool recordedOpus(const char* path,double multiplier,int64_t expectedFrames) {
        pause();stopDecoder();
        if(!path || !(multiplier>0 && multiplier<=1) || expectedFrames<2)return false;
        const int64_t t0 = monotonicNowMs();
        auto opus = std::make_unique<OggOpusStream>();
        if (!opus->open(path, expectedFrames)) return false;
        const size_t totalFrames = static_cast<size_t>(expectedFrames);
        auto buf = std::unique_ptr<float[]>(new float[totalFrames * kOpusChannels]);
        const size_t initialGoal = std::min(totalFrames, kOpusInitialPrefillFrames);
        if (!opus->decodeUntil(buf.get(), initialGoal, nullptr) || opus->writtenFrames < initialGoal) {
            return false;
        }
        const int64_t t1 = monotonicNowMs();
        backingData = std::move(buf);
        backingTotalFrames = totalFrames;
        backingRate = kOpusSampleRate;
        backingSpeed = multiplier;
        backingReadyFrames.store(opus->writtenFrames, std::memory_order_release);
        events.clear();commands.clear();for(auto& v:voices)v.id=-1;
        voiceCount.store(0);
        if (opus->writtenFrames < totalFrames) {
            {
                std::lock_guard<std::mutex> lock(decoderMutex);
                decoderFinished = false;
            }
            decoderCancel.store(false, std::memory_order_relaxed);
            decoderThread = std::thread([this, s = std::move(opus), totalFrames, t0]() mutable {
                float* dst = backingData.get();
                while (!s->eof && !s->error && s->writtenFrames < totalFrames &&
                       !decoderCancel.load(std::memory_order_acquire)) {
                    const size_t nextGoal = std::min(totalFrames, s->writtenFrames + kOpusBackgroundChunkFrames);
                    if (!s->decodeUntil(dst, nextGoal, &decoderCancel)) break;
                    backingReadyFrames.store(s->writtenFrames, std::memory_order_release);
                    { std::lock_guard<std::mutex> lk(decoderMutex); }
                    decoderCv.notify_all();
                }
                if (!decoderCancel.load(std::memory_order_acquire)) {
                    if (s->writtenFrames < totalFrames) {
                        const size_t missingSamples = (totalFrames - s->writtenFrames) * kOpusChannels;
                        std::memset(dst + s->writtenFrames * kOpusChannels, 0, missingSamples * sizeof(float));
                    }
                    backingReadyFrames.store(totalFrames, std::memory_order_release);
                    __android_log_print(ANDROID_LOG_INFO, "PerformanceTiming",
                        "Native libopus background complete: total=%lldms (%zu frames)",
                        static_cast<long long>(monotonicNowMs() - t0), totalFrames);
                }
                {
                    std::lock_guard<std::mutex> lk(decoderMutex);
                    decoderFinished = true;
                }
                decoderCv.notify_all();
            });
        }
        __android_log_print(ANDROID_LOG_INFO, "PerformanceTiming",
            "Native libopus prefill ready: %lldms (%zu/%zu frames)",
            static_cast<long long>(t1 - t0), backingReadyFrames.load(std::memory_order_relaxed), totalFrames);
        return true;
    }
    bool begin(double seconds,double stopSeconds,double multiplier,int mask,double bpm,int beats) {
        pause();if(failed.load() || (events.empty() && backingTotalFrames==0) || !(multiplier>0) ||
            !std::isfinite(seconds) || !std::isfinite(stopSeconds) || !(stopSeconds>seconds) || beats<0)return false;
        if(backingTotalFrames>0 && (std::abs(multiplier-backingSpeed)>1e-6 ||
            stopSeconds>static_cast<double>(backingTotalFrames)/backingRate*backingSpeed+1e-5))return false;
        if(backingTotalFrames>0) {
            const size_t startCursor=static_cast<size_t>(std::max(0.0,seconds*backingRate/backingSpeed));
            const size_t needFrames=std::min(backingTotalFrames,startCursor+static_cast<size_t>(backingRate));
            if(backingReadyFrames.load(std::memory_order_acquire)<needFrames) {
                std::unique_lock<std::mutex> lk(decoderMutex);
                decoderCv.wait(lk,[&](){
                    return decoderFinished || backingReadyFrames.load(std::memory_order_acquire)>=needFrames;
                });
            }
        }
        commands.clear();for(auto& v:voices)v.id=-1;
        frame=std::max(0.0,seconds*rate);startFrame=frame;endFrame=stopSeconds*rate;speed=multiplier;modeMask=mask;
        smoothedLatencyFrames=-1.0;
        lastReportedUs=static_cast<int64_t>(frame/rate*1000000);
        next=static_cast<size_t>(std::lower_bound(events.begin(),events.end(),frame,[](const Event&e,double f){return e.start<f;})-events.begin());
        if(backingTotalFrames==0)restoreAutomaticVoices();
        backingCursor=frame/rate*backingRate/backingSpeed;
        visibleUs.store(lastReportedUs);
        beatFrames=static_cast<int64_t>(rate*60.0/(std::max(1.0,bpm)*speed));
        countRemaining=beatFrames*beats;countBeat.store(beats);clickRemaining=0;
        active.store(true,std::memory_order_release);
        const auto result=stream->requestStart();if(result!=oboe::Result::OK)active.store(false);
        return result==oboe::Result::OK;
    }
    // Touch commands cannot add, remove or mute notes during complete-track playback.
    bool hit(int id,double elapsedSeconds) {return backingTotalFrames==0 && active.load() && !countBeat.load() && commands.push({1,id,elapsedSeconds*rate});}
    bool release(int id) {return backingTotalFrames==0 && commands.push({2,id,0});}
    int64_t position() const {return visibleUs.load(std::memory_order_acquire);}
    int counting() const {return countBeat.load();}
    bool playing() const {return active.load();}
    bool hasFailed() const {return failed.load();}
    int sampleRate() const {return rate;}
    int playingVoices() const {return voiceCount.load();}
    void setMuted(bool value) {muted.store(value,std::memory_order_release);}
    float outputPeak() const {return lastOutputPeak.load(std::memory_order_acquire);}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream,void* data,int32_t numFrames) override {
        auto* output=static_cast<float*>(data);
        std::fill(output,output+numFrames*2,0);
        if(!active.load(std::memory_order_acquire)){lastOutputPeak.store(0);return oboe::DataCallbackResult::Continue;}
        const bool wasCounting = countRemaining > 0;
        Command command;
        while(commands.pop(command)) {
            if(command.kind==1)voice(command.id,command.elapsed);
            else for(auto& v:voices)if(v.id==command.id && v.release<0)v.release=releaseFrames;
        }
        for(int32_t f=0;f<numFrames;f++) {
            if(!active.load(std::memory_order_relaxed))break;
            if(countRemaining>0) {
                if(backingTotalFrames==0 && countRemaining%beatFrames==0)clickRemaining=static_cast<int64_t>(rate*.045);
                if(clickRemaining>0) {
                    const double t=static_cast<double>(clickRemaining)/rate;
                    const float click=static_cast<float>(std::sin(t*2*3.141592653589793*880)*t/.045*.15);
                    output[2*f]=output[2*f+1]=click;--clickRemaining;
                }
                --countRemaining;countBeat.store(static_cast<int>((countRemaining+beatFrames-1)/beatFrames));continue;
            }
            if(backingTotalFrames>0) {
                const auto index=static_cast<size_t>(backingCursor);
                const auto length=backingTotalFrames;
                if(index>=length){active.store(false);break;}
                const auto following=std::min(index+1,length-1);
                const auto ready=backingReadyFrames.load(std::memory_order_acquire);
                if(following<ready) {
                    const float blend=static_cast<float>(backingCursor-index);
                    const float* pcm=backingData.get();
                    for(int channel=0;channel<2;channel++) {
                        const float a=pcm[index*2+channel], b=pcm[following*2+channel];
                        output[2*f+channel]=std::clamp(a+(b-a)*blend,-1.f,1.f);
                    }
                } else {
                    output[2*f]=output[2*f+1]=0.0f;
                }
                // Sample-rate conversion only; the prepared variant already has the chosen tempo.
                backingCursor+=static_cast<double>(backingRate)/rate;
                frame=std::min(frame+speed,endFrame);
                if(frame>=endFrame){active.store(false);break;}
                continue;
            }
            while(next<events.size() && events[next].start<=frame) {
                if(!(events[next].mask&modeMask))voice(static_cast<int>(next));
                ++next;
            }
            float mixed=0;
            for(auto& v:voices) {
                if(v.id<0)continue;
                const auto& pcm=samples[v.sample].pcm;
                const auto index=static_cast<size_t>(v.cursor);
                if(index+1>=pcm.size() || v.release==0){v.id=-1;continue;}
                if(frame>=v.end && v.release<0)v.release=releaseFrames;
                const float ratio=static_cast<float>(v.cursor-index);
                float envelope=1;
                if(v.release>0){envelope=static_cast<float>(v.release)/releaseFrames;envelope*=envelope;--v.release;}
                mixed+=(pcm[index]+(pcm[index+1]-pcm[index])*ratio)*v.gain*envelope;
                v.cursor+=v.step;
            }
            // Bounded soft saturation prevents clipping on dense chords; no callback allocation or I/O.
            mixed*=master;mixed=mixed/(1+std::abs(mixed)*.3f);
            output[2*f]=output[2*f+1]=std::clamp(mixed,-.98f,.98f);
            frame+=speed;
            if(frame>=endFrame){active.store(false);break;}
        }
        // Mute only the final output: the recording, note voices and source clock keep advancing.
        // This also covers the preparation clicks and both recorded/synthesized playback paths.
        const bool silent=muted.load(std::memory_order_acquire);
        float peak=0;
        for(int32_t i=0;i<numFrames*2;i++) {
            if(silent)output[i]=0;
            peak=std::max(peak,std::abs(output[i]));
        }
        lastOutputPeak.store(peak,std::memory_order_release);
        if(!active.load(std::memory_order_relaxed) && frame>=endFrame) {
            lastReportedUs=static_cast<int64_t>(endFrame/rate*1000000);
            visibleUs.store(lastReportedUs,std::memory_order_release);
        } else if(wasCounting && countRemaining>0) {
            visibleUs.store(lastReportedUs,std::memory_order_release);
        } else {
            double rawLatency=-1.0;
            if(audioStream!=nullptr) {
                const auto ts=audioStream->getTimestamp(CLOCK_MONOTONIC);
                if(ts) {
                    struct timespec nowTs{};
                    double elapsedFrames=0.0;
                    if(clock_gettime(CLOCK_MONOTONIC,&nowTs)==0) {
                        const int64_t nowNs=static_cast<int64_t>(nowTs.tv_sec)*1000000000LL+nowTs.tv_nsec;
                        const double elapsedSec=static_cast<double>(nowNs-ts.value().timestamp)*1e-9;
                        elapsedFrames=std::clamp(elapsedSec*rate,-rate*0.05,rate*0.1);
                    }
                    const double hwPosNow=static_cast<double>(ts.value().position)+elapsedFrames;
                    const double written=static_cast<double>(audioStream->getFramesWritten()+numFrames);
                    const double delta=written-hwPosNow;
                    if(delta>=0.0 && delta<=rate*0.18)rawLatency=std::min(delta,rate*0.06);
                }
                if(rawLatency<0.0) {
                    rawLatency=std::clamp(static_cast<double>(audioStream->getBufferSizeInFrames()),0.0,rate*0.06);
                }
            } else {
                rawLatency=0.0;
            }
            if(smoothedLatencyFrames<0.0)smoothedLatencyFrames=rawLatency;
            else smoothedLatencyFrames=smoothedLatencyFrames*0.85+rawLatency*0.15;
            // Offset audio buffer latency against ~25ms visual presentation pipeline so notes do not lag behind sound.
            const double effectiveLatencyFrames=std::max(0.0,smoothedLatencyFrames-rate*0.025);
            const double presentedFrame=std::clamp(frame-effectiveLatencyFrames*speed,startFrame,endFrame);
            const int64_t candidateUs=static_cast<int64_t>(presentedFrame/rate*1000000);
            if(candidateUs>lastReportedUs)lastReportedUs=candidateUs;
            visibleUs.store(lastReportedUs,std::memory_order_release);
        }
        voiceCount.store(static_cast<int>(std::count_if(voices.begin(),voices.end(),[](const Voice& v){return v.id>=0;})));
        return oboe::DataCallbackResult::Continue;
    }
    void onErrorAfterClose(oboe::AudioStream*,oboe::Result) override {
        // Reopening and resource work belongs to the UI/control thread after checkpointing.
        active.store(false);failed.store(true,std::memory_order_release);
    }
#ifndef NDEBUG
    void disconnectForTest() {
        pause();if(stream)stream->close();
        onErrorAfterClose(nullptr,oboe::Result::ErrorDisconnected);
    }
#endif
};
PianoEngine* engine(jlong handle){return reinterpret_cast<PianoEngine*>(handle);}
}

extern "C" JNIEXPORT jlong JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_create(JNIEnv*,jobject) {
    auto value=std::make_unique<PianoEngine>();if(!value->open())return 0;return reinterpret_cast<jlong>(value.release());
}
extern "C" JNIEXPORT void JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_destroy(JNIEnv*,jobject,jlong h){delete engine(h);}
#ifndef NDEBUG
extern "C" JNIEXPORT void JNICALL Java_cn_musicpilgrimage_app_PerformanceInterruptionTest_disconnectNative(JNIEnv*,jobject,jlong h){engine(h)->disconnectForTest();}
#endif
extern "C" JNIEXPORT void JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_setSample(JNIEnv* env,jobject,jlong h,jint root,jint rate,jfloatArray array) {
    std::vector<float> pcm(static_cast<size_t>(env->GetArrayLength(array)));env->GetFloatArrayRegion(array,0,static_cast<jsize>(pcm.size()),pcm.data());engine(h)->sample(root,rate,std::move(pcm));
}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_setScore(JNIEnv* env,jobject,jlong h,jdoubleArray array) {
    std::vector<double> score(static_cast<size_t>(env->GetArrayLength(array)));env->GetDoubleArrayRegion(array,0,static_cast<jsize>(score.size()),score.data());return engine(h)->score(score);
}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_setBacking(JNIEnv* env,jobject,jlong h,jint rate,jdouble speed,jfloatArray array) {
    std::vector<float> pcm(static_cast<size_t>(env->GetArrayLength(array)));env->GetFloatArrayRegion(array,0,static_cast<jsize>(pcm.size()),pcm.data());return engine(h)->recorded(rate,speed,std::move(pcm));
}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_setOpusBacking(JNIEnv* env,jobject,jlong h,jstring jpath,jdouble speed,jint expectedFrames) {
    if (!jpath || expectedFrames <= 0) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;
    const bool ok = engine(h)->recordedOpus(path, speed, expectedFrames);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ok) {
        __android_log_print(ANDROID_LOG_WARN, "PerformanceTiming", "Native libopus decode failed, will fallback");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_start(JNIEnv*,jobject,jlong h,jdouble start,jdouble end,jdouble speed,jint mask,jdouble bpm,jint beats){return engine(h)->begin(start,end,speed,mask,bpm,beats);}
extern "C" JNIEXPORT void JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_pause(JNIEnv*,jobject,jlong h){engine(h)->pause();}
extern "C" JNIEXPORT jlong JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_position(JNIEnv*,jobject,jlong h){return engine(h)->position();}
extern "C" JNIEXPORT jint JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_counting(JNIEnv*,jobject,jlong h){return engine(h)->counting();}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_isPlaying(JNIEnv*,jobject,jlong h){return engine(h)->playing();}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_failed(JNIEnv*,jobject,jlong h){return engine(h)->hasFailed();}
extern "C" JNIEXPORT jint JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_sampleRate(JNIEnv*,jobject,jlong h){return engine(h)->sampleRate();}
extern "C" JNIEXPORT jint JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_playingVoices(JNIEnv*,jobject,jlong h){return engine(h)->playingVoices();}
extern "C" JNIEXPORT void JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_setMuted(JNIEnv*,jobject,jlong h,jboolean muted){engine(h)->setMuted(muted);}
extern "C" JNIEXPORT jfloat JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_outputPeak(JNIEnv*,jobject,jlong h){return engine(h)->outputPeak();}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_hit(JNIEnv*,jobject,jlong h,jint id,jdouble elapsed){return engine(h)->hit(id,elapsed);}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_musicpilgrimage_app_performance_NativePiano_release(JNIEnv*,jobject,jlong h,jint id){return engine(h)->release(id);}
