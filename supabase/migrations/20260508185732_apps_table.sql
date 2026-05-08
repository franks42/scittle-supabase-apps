-- Phase 3c: apps registry.
--
-- Each row is an app the launcher can list and dispatch to. The cljs
-- source for an app lives in ns_modules under a namespace name; that
-- namespace's start! is what the dispatcher calls. RLS mirrors
-- ns_modules: read-all, insert-authenticated.

create table public.apps (
  id           text primary key,
  display_name text not null,
  description  text,
  root_ns      text not null,
  background   text default '#0f766e',
  published    boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid references auth.users(id)
);

create index apps_published_idx
  on public.apps (published)
  where published = true;

alter table public.apps enable row level security;

create policy "apps read all"
  on public.apps
  for select
  using (true);

create policy "apps insert authenticated"
  on public.apps
  for insert
  with check (auth.uid() is not null);
