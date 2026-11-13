alter table public.discover_tracks
    add column collection text not null default '' check (char_length(collection) <= 120),
    add column search_aliases text not null default '' check (char_length(search_aliases) <= 500),
    add column download_allowed boolean not null default false;

create or replace view public.discover_my_published_tracks with (security_invoker = true) as
select track.id, track.title, track.artist, track.album, track.duration_ms,
       track.audio_url, track.artwork_url, track.published_at,
       track.collection, track.search_aliases, track.download_allowed
from public.discover_tracks as track
join public.discover_track_submitters as submitter on submitter.track_id = track.id
where track.published;

create table public.discover_playlists (
    id uuid primary key,
    owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    name text not null check (char_length(btrim(name)) between 1 and 100),
    track_ids uuid[] not null default '{}' check (cardinality(track_ids) <= 250 and array_position(track_ids, null) is null),
    revision bigint not null default 0 check (revision >= 0)
);

create index discover_playlists_owner_id_idx on public.discover_playlists (owner_id);
alter table public.discover_playlists enable row level security;
revoke all on public.discover_playlists from public, anon, authenticated;
grant select (id, name, track_ids, revision), insert (id, name, track_ids),
      update (name, track_ids, revision), delete on public.discover_playlists to authenticated;

create policy "Read own playlists" on public.discover_playlists
    for select to authenticated using (owner_id = (select auth.uid()));
create policy "Create own playlists" on public.discover_playlists
    for insert to authenticated with check (owner_id = (select auth.uid()));
create policy "Update own playlists" on public.discover_playlists
    for update to authenticated using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));
create policy "Delete own playlists" on public.discover_playlists
    for delete to authenticated using (owner_id = (select auth.uid()));
