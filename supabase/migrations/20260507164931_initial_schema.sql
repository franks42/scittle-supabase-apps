-- Phase 1: ns_modules schema, closure/impact RPCs, and RLS policies.
-- Realizes §4.2 of docs/scittle-on-supabase.md.

-- ============================================================
-- ns_modules: ClojureScript namespaces stored as data.
-- One row per (name, version). Edits create new rows by
-- bumping version; the launcher and editor read the latest
-- version per name through the ns_modules_current view.
-- ============================================================

create table public.ns_modules (
  name        text not null,
  version     int  not null default 1,
  source      text not null,
  deps        text[] not null default '{}',
  refs        text[] not null default '{}',
  description text,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references auth.users(id),
  primary key (name, version)
);

-- GIN index accelerates reverse-dependency queries
-- (ns_impact walks `where ... = any(m.deps)`).
create index ns_modules_deps_gin
  on public.ns_modules using gin (deps);

-- updated_at refresh trigger.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end$$;

create trigger ns_modules_set_updated_at
  before update on public.ns_modules
  for each row execute function public.set_updated_at();

-- ============================================================
-- ns_modules_current: latest version of each namespace.
-- security_invoker so RLS on the underlying table applies to
-- view readers as if they queried the table directly.
-- ============================================================

create view public.ns_modules_current
  with (security_invoker = true)
as
  select distinct on (name) *
  from public.ns_modules
  order by name, version desc;

-- ============================================================
-- ns_closure(root): forward transitive closure starting at
-- `root`. Returns rows in arbitrary order; the caller
-- topo-sorts on `deps` before evaluating.
-- ============================================================

create or replace function public.ns_closure(root text)
returns table (name text, source text, deps text[], version int)
language sql
stable
as $$
  with recursive walk(name) as (
    select root
    union
    select unnest(m.deps)
    from public.ns_modules_current m
    join walk w on w.name = m.name
  )
  select m.name, m.source, m.deps, m.version
  from public.ns_modules_current m
  join walk w on w.name = m.name;
$$;

-- ============================================================
-- ns_impact(target): reverse transitive closure — namespaces
-- whose behavior could change if `target` is updated. Used
-- by the editor for "this change will affect: …" warnings.
-- ============================================================

create or replace function public.ns_impact(target text)
returns table (name text)
language sql
stable
as $$
  with recursive walk(name) as (
    select target
    union
    select m.name
    from public.ns_modules_current m
    join walk w on w.name = any(m.deps)
  )
  select name from walk where name <> target;
$$;

-- ============================================================
-- Row Level Security.
--
-- Read: open by default for both anon and authenticated. The
-- launcher must work for unauthenticated visitors. Tighten
-- later by gating on auth.role() = 'authenticated' or by
-- introducing a `published` flag.
--
-- Insert: only authenticated users can write. Updates/deletes
-- are not exposed via RLS — editing happens by inserting a new
-- (name, version+1) row. Schema and policy changes go through
-- CLI/dashboard with the secret key, which bypasses RLS.
-- ============================================================

alter table public.ns_modules enable row level security;

create policy "ns_modules read all"
  on public.ns_modules
  for select
  using (true);

create policy "ns_modules insert authenticated"
  on public.ns_modules
  for insert
  with check (auth.uid() is not null);
