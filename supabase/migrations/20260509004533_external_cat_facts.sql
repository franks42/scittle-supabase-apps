-- Phase 4: scheduled ingestion via pg_net + pg_cron, async pattern.
--
-- pg_net 0.20's http_collect_response(async := false) blocks until the
-- request completes, which trips per-statement timeouts on Supabase's
-- pooled connections. Instead we use a two-stage pattern:
--
--   1. enqueue_cat_fact_fetch  — fast: calls net.http_get, records
--      the request id in cat_fact_pending_requests, returns.
--   2. harvest_cat_facts       — fast: joins pending requests against
--      net._http_response, inserts successful payloads into
--      external_cat_facts, removes the pending row.
--
-- Both functions run via pg_cron on independent schedules. Errors on
-- the upstream API just leave a pending row that pg_net will eventually
-- prune (default response TTL = 6 hours).

create extension if not exists pg_net;
create extension if not exists pg_cron;

create table if not exists public.external_cat_facts (
  id          bigserial primary key,
  fact        text not null,
  length      int,
  fetched_at  timestamptz not null default now()
);

create index if not exists external_cat_facts_fetched_at_idx
  on public.external_cat_facts (fetched_at desc);

create table if not exists public.cat_fact_pending_requests (
  request_id  bigint primary key,
  enqueued_at timestamptz not null default now()
);

alter table public.external_cat_facts enable row level security;
alter table public.cat_fact_pending_requests enable row level security;

drop policy if exists "external_cat_facts read all" on public.external_cat_facts;
create policy "external_cat_facts read all"
  on public.external_cat_facts
  for select
  using (true);

-- Stage 1: enqueue. Fast — just queues the request and tracks the id.
create or replace function public.enqueue_cat_fact_fetch()
returns void
language plpgsql
security definer
as $$
declare
  rid bigint;
begin
  rid := net.http_get(url := 'https://catfact.ninja/fact');
  insert into public.cat_fact_pending_requests (request_id) values (rid);
end;
$$;

-- Stage 2: harvest. Fast — joins our pending requests against pg_net's
-- response table, inserts successful payloads, drops the pending row.
create or replace function public.harvest_cat_facts()
returns void
language plpgsql
security definer
as $$
declare
  r    record;
  body jsonb;
begin
  for r in
    select p.request_id, hr.status_code, hr.content, hr.error_msg
      from public.cat_fact_pending_requests p
      join net._http_response hr on hr.id = p.request_id
  loop
    if r.status_code = 200 and r.content is not null then
      body := r.content::jsonb;
      if body ? 'fact' then
        insert into public.external_cat_facts (fact, length)
        values (body->>'fact', nullif(body->>'length', '')::int);
      end if;
    end if;
    -- Drop the pending row whether it succeeded or failed; failures
    -- get retried by the next enqueue tick.
    delete from public.cat_fact_pending_requests
      where request_id = r.request_id;
  end loop;
end;
$$;

-- Cron jobs. Re-run the migration is a no-op: unschedule first if a
-- prior version exists, then re-schedule.
do $$
begin
  begin perform cron.unschedule('cat-fact-enqueue'); exception when others then null; end;
  begin perform cron.unschedule('cat-fact-harvest'); exception when others then null; end;
  -- Tolerate the older single-job name from earlier development.
  begin perform cron.unschedule('ingest-cat-fact'); exception when others then null; end;
end$$;

select cron.schedule(
  'cat-fact-enqueue',
  '*/5 * * * *',
  $$ select public.enqueue_cat_fact_fetch() $$
);

select cron.schedule(
  'cat-fact-harvest',
  '* * * * *',
  $$ select public.harvest_cat_facts() $$
);

-- Ditch the single-stage function from earlier development if present.
drop function if exists public.ingest_cat_fact();
