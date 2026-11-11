create table public.discover_tracks (
    id uuid primary key default gen_random_uuid(),
    title text not null check (btrim(title) <> ''),
    artist text not null default '',
    duration_ms integer not null check (duration_ms > 0),
    audio_url text not null check (audio_url like 'https://%'),
    artwork_url text check (artwork_url like 'https://%'),
    published boolean not null default false,
    published_at timestamptz not null default now()
);

create index discover_tracks_published_order
    on public.discover_tracks (published_at desc, id) where published;

create table public.discover_likes (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    track_id uuid not null references public.discover_tracks(id) on delete cascade,
    primary key (user_id, track_id)
);

alter table public.discover_tracks enable row level security;
alter table public.discover_likes enable row level security;

revoke all on public.discover_tracks, public.discover_likes from public, anon, authenticated;
grant select on public.discover_tracks to anon, authenticated;
grant select, insert, delete on public.discover_likes to authenticated;
grant all on public.discover_tracks, public.discover_likes to service_role;

create policy "Read published Discover tracks" on public.discover_tracks
    for select to anon, authenticated using (published);

create policy "Read own likes" on public.discover_likes
    for select to authenticated using (user_id = (select auth.uid()));

create policy "Like a published track as yourself" on public.discover_likes
    for insert to authenticated with check (
        user_id = (select auth.uid())
        and exists (select 1 from public.discover_tracks where id = discover_likes.track_id and published)
    );

create policy "Delete own likes" on public.discover_likes
    for delete to authenticated using (user_id = (select auth.uid()));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('discover', 'discover', true, 50000000,
        array['audio/mpeg', 'image/jpeg', 'image/png', 'image/webp']);
