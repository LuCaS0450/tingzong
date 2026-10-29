-- Run after reviewing and publishing the initial question pool.
begin;
set local search_path=v2,extensions,public;
do $$
declare pool text[];n integer;day date;start_day date=(now() at time zone 'Asia/Shanghai')::date;
begin
 select array_agg(id order by id) into pool from questions where published;
 n=coalesce(array_length(pool,1),0);
 if n=0 then raise notice 'No reviewed questions: schedule not generated';return;end if;
 -- Existing published dates are immutable; only future dates can be replanned.
 for i in 0..364 loop
  day=start_day+i;
  insert into challenge_schedule(challenge_date,question_id) values(day,pool[(i%n)+1])
  on conflict(challenge_date) do update set question_id=excluded.question_id where challenge_schedule.challenge_date>start_day;
 end loop;
end $$;
commit;
