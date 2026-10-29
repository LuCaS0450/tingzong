#include <oboe/Oboe.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace {
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
    // Complete stereo track, stretched off the audio thread without changing pitch.
    std::vector<float> backing;
    int backingRate=44100;
    double backingSpeed=1, backingCursor=0;
    std::array<Voice,96> voices{};
    Queue commands;
    std::atomic<bool> active{false}, failed{false}, muted{false};
    std::atomic<float> lastOutputPeak{0};
    std::atomic<int64_t> visibleUs{0};
    std::atomic<int> countBeat{0};
    std::atomic<int> voiceCount{0};
    double frame=0, endFrame=0, speed=1;
    int rate=48000, modeMask=0, releaseFrames=11520;
    size_t next=0;
    int64_t countRemaining=0, beatFrames=0, clickRemaining=0;
    float master=.42f;

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
    ~PianoEngine() {pause();if(stream)stream->close();}
    void sample(int root,int sampleRate,std::vector<float> pcm) {
        pause();samples.push_back(Sample{root,sampleRate,std::move(pcm)});
    }
    bool score(const std::vector<double>& data) {
        pause();events.clear();backing.clear();commands.clear();
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
        pause();
        if(sampleRate<8000 || sampleRate>192000 || !(multiplier>0 && multiplier<=1) ||
            pcm.size()<4 || pcm.size()%2!=0 || !std::all_of(pcm.begin(),pcm.end(),[](float v){return std::isfinite(v);}))return false;
        backing=std::move(pcm);backingRate=sampleRate;backingSpeed=multiplier;
        events.clear();commands.clear();for(auto& v:voices)v.id=-1;
        voiceCount.store(0);return true;
    }
    bool begin(double seconds,double stopSeconds,double multiplier,int mask,double bpm,int beats) {
        pause();if(failed.load() || (events.empty() && backing.empty()) || !(multiplier>0) ||
            !std::isfinite(seconds) || !std::isfinite(stopSeconds) || !(stopSeconds>seconds) || beats<0)return false;
        if(!backing.empty() && (std::abs(multiplier-backingSpeed)>1e-6 ||
            stopSeconds>static_cast<double>(backing.size()/2)/backingRate*backingSpeed+1e-5))return false;
        commands.clear();for(auto& v:voices)v.id=-1;
        frame=std::max(0.0,seconds*rate);endFrame=stopSeconds*rate;speed=multiplier;modeMask=mask;
        next=static_cast<size_t>(std::lower_bound(events.begin(),events.end(),frame,[](const Event&e,double f){return e.start<f;})-events.begin());
        if(backing.empty())restoreAutomaticVoices();
        backingCursor=frame/rate*backingRate/backingSpeed;
        visibleUs.store(static_cast<int64_t>(frame/rate*1000000));
        beatFrames=static_cast<int64_t>(rate*60.0/(std::max(1.0,bpm)*speed));
        countRemaining=beatFrames*beats;countBeat.store(beats);clickRemaining=0;
        active.store(true,std::memory_order_release);
        const auto result=stream->requestStart();if(result!=oboe::Result::OK)active.store(false);
        return result==oboe::Result::OK;
    }
    // Touch commands cannot add, remove or mute notes during complete-track playback.
    bool hit(int id,double elapsedSeconds) {return backing.empty() && active.load() && !countBeat.load() && commands.push({1,id,elapsedSeconds*rate});}
    bool release(int id) {return backing.empty() && commands.push({2,id,0});}
    int64_t position() const {return visibleUs.load(std::memory_order_acquire);}
    int counting() const {return countBeat.load();}
    bool playing() const {return active.load();}
    bool hasFailed() const {return failed.load();}
    int sampleRate() const {return rate;}
    int playingVoices() const {return voiceCount.load();}
    void setMuted(bool value) {muted.store(value,std::memory_order_release);}
    float outputPeak() const {return lastOutputPeak.load(std::memory_order_acquire);}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void* data,int32_t numFrames) override {
        auto* output=static_cast<float*>(data);
        std::fill(output,output+numFrames*2,0);
        if(!active.load(std::memory_order_acquire)){lastOutputPeak.store(0);return oboe::DataCallbackResult::Continue;}
        Command command;
        while(commands.pop(command)) {
            if(command.kind==1)voice(command.id,command.elapsed);
            else for(auto& v:voices)if(v.id==command.id && v.release<0)v.release=releaseFrames;
        }
        for(int32_t f=0;f<numFrames;f++) {
            if(!active.load(std::memory_order_relaxed))break;
            if(countRemaining>0) {
                if(backing.empty() && countRemaining%beatFrames==0)clickRemaining=static_cast<int64_t>(rate*.045);
                if(clickRemaining>0) {
                    const double t=static_cast<double>(clickRemaining)/rate;
                    const float click=static_cast<float>(std::sin(t*2*3.141592653589793*880)*t/.045*.15);
                    output[2*f]=output[2*f+1]=click;--clickRemaining;
                }
                --countRemaining;countBeat.store(static_cast<int>((countRemaining+beatFrames-1)/beatFrames));continue;
            }
            if(!backing.empty()) {
                const auto index=static_cast<size_t>(backingCursor);
                const auto length=backing.size()/2;
                if(index>=length){active.store(false);break;}
                const auto following=std::min(index+1,length-1);
                const float blend=static_cast<float>(backingCursor-index);
                for(int channel=0;channel<2;channel++) {
                    const float a=backing[index*2+channel], b=backing[following*2+channel];
                    output[2*f+channel]=std::clamp(a+(b-a)*blend,-1.f,1.f);
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
        visibleUs.store(static_cast<int64_t>(frame/rate*1000000),std::memory_order_release);
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
