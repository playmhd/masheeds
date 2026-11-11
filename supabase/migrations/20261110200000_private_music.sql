create table public.private_tracks (
    id uuid primary key,
    owner_id uuid references auth.users(id) on delete set null,
    object_key text not null unique,
    title text not null check (char_length(title) between 1 and 200),
    artist text not null default '' check (char_length(artist) <= 200),
    duration_ms integer not null default 0 check (duration_ms between 0 and 86400000),
    size_bytes integer not null check (size_bytes between 1 and 20000000),
    mime_type text not null check (mime_type in ('audio/mpeg', 'audio/mp4')),
    state text not null default 'pending' check (state in ('pending', 'verifying', 'ready', 'deleting')),
    created_at timestamptz not null default now(),
    upload_until timestamptz not null,
    finish_until timestamptz not null,
    verify_token uuid,
    verify_until timestamptz,
    staging_pending boolean not null default true
);

create index private_tracks_owner on public.private_tracks (owner_id, created_at desc, id);
create index private_tracks_cleanup on public.private_tracks (created_at, id)
    where state <> 'ready' or staging_pending or owner_id is null;

alter table public.private_tracks enable row level security;
revoke all on public.private_tracks from public, anon, authenticated;
grant select (id, title, artist, duration_ms, size_bytes, mime_type, state, created_at)
    on public.private_tracks to authenticated;
grant all on public.private_tracks to service_role;

create policy "Read your ready private tracks" on public.private_tracks for select to authenticated
    using (owner_id = (select auth.uid()) and state = 'ready');

-- The owner lock covers both the quota check and reservation, including concurrent requests.
create function public.reserve_private_track(
    p_owner uuid, p_id uuid, p_title text, p_artist text, p_duration integer, p_size integer, p_mime text
) returns jsonb
language plpgsql set search_path = '' as $$
declare
    used_bytes bigint;
    pending_count bigint;
    reserved public.private_tracks;
begin
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_owner::text, 4201));
    select coalesce(sum(size_bytes), 0), count(*) filter (where state <> 'ready')
      into used_bytes, pending_count from public.private_tracks where owner_id = p_owner;
    if used_bytes + p_size > 100000000 then
        raise exception using errcode = 'P0001', message = 'quota_exceeded';
    end if;
    if pending_count >= 5 then
        raise exception using errcode = 'P0001', message = 'too_many_uploads';
    end if;
    insert into public.private_tracks
        (id, owner_id, object_key, title, artist, duration_ms, size_bytes, mime_type, upload_until, finish_until)
    values (p_id, p_owner, 'music/' || p_owner || '/' || p_id,
            p_title, p_artist, p_duration, p_size, p_mime, now() + interval '5 minutes', now() + interval '20 minutes')
    returning * into reserved;
    return to_jsonb(reserved);
end;
$$;

revoke all on function public.reserve_private_track(uuid, uuid, text, text, integer, integer, text)
    from public, anon, authenticated;
grant execute on function public.reserve_private_track(uuid, uuid, text, text, integer, integer, text)
    to service_role;
