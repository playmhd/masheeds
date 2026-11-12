create table public.discover_track_submitters (
    track_id uuid primary key references public.discover_tracks(id) on delete cascade,
    owner_id uuid not null references auth.users(id) on delete cascade
);

create index discover_track_submitters_owner_id_idx
    on public.discover_track_submitters (owner_id);

alter table public.discover_track_submitters enable row level security;
revoke all on public.discover_track_submitters from public, anon, authenticated, service_role;
grant select (track_id) on public.discover_track_submitters to authenticated;
grant insert (track_id, owner_id) on public.discover_track_submitters to service_role;

create policy "Read own published track links" on public.discover_track_submitters
    for select to authenticated using (owner_id = (select auth.uid()));

create view public.discover_my_published_tracks with (security_invoker = true) as
select track.id, track.title, track.artist, track.album, track.duration_ms,
       track.audio_url, track.artwork_url, track.published_at
from public.discover_tracks as track
join public.discover_track_submitters as submitter on submitter.track_id = track.id
where track.published;

revoke all on public.discover_my_published_tracks from public, anon, authenticated, service_role;
grant select on public.discover_my_published_tracks to authenticated;

-- Reviewer uses the submission ID for the catalog row. Capture the owner
-- in the same transaction that creates the track, before queue cleanup.
create function discover_submission_private.remember_published_submitter()
returns trigger language plpgsql security invoker set search_path = '' as $$
begin
    insert into public.discover_track_submitters (track_id, owner_id)
    select new.id, submission.owner_id
    from public.discover_submissions as submission
    where submission.id = new.id and submission.owner_id is not null;
    return new;
end;
$$;

revoke all on function discover_submission_private.remember_published_submitter()
    from public, anon, authenticated;

create trigger remember_discover_track_submitter
    after insert on public.discover_tracks
    for each row execute function discover_submission_private.remember_published_submitter();

-- Recover only links still supported by a submission row.
insert into public.discover_track_submitters (track_id, owner_id)
select track.id, submission.owner_id
from public.discover_tracks as track
join public.discover_submissions as submission on submission.id = track.id
where submission.owner_id is not null;
