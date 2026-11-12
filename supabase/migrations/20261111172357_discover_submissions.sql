create schema discover_submission_private;

create table public.discover_submissions (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid references auth.users(id) on delete set null,
    slot integer not null unique check (slot between 1 and 30),
    title text not null check (char_length(btrim(title)) between 1 and 200),
    artist text not null check (char_length(btrim(artist)) between 1 and 200),
    source text not null check (char_length(btrim(source)) between 1 and 500),
    duration_ms integer not null check (duration_ms between 1 and 86400000),
    byte_size bigint not null check (byte_size between 1 and 10485760),
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    reviewing boolean not null default false,
    created_at timestamptz not null default now(),
    unique (owner_id, sha256)
);

alter table public.discover_submissions enable row level security;
revoke all on public.discover_submissions from public, anon, authenticated;
grant select (owner_id, slot, reviewing) on public.discover_submissions to authenticated;
grant all on public.discover_submissions to service_role;

create policy "Read own submission slots" on public.discover_submissions
    for select to authenticated using (owner_id = (select auth.uid()));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('discover-submissions', 'discover-submissions', false, 10485760, array['audio/mpeg']);

-- Thirty fixed object keys bound stored submissions to 300 MiB, even if a slot
-- needs cleanup after an interrupted transfer.
create policy "Upload to your reserved submission slot" on storage.objects
    for insert to authenticated with check (
        bucket_id = 'discover-submissions'
        and exists (
            select 1 from public.discover_submissions as submission
            where submission.owner_id = (select auth.uid())
              and storage.objects.name = submission.slot::text || '.mp3'
              and not submission.reviewing
        )
    );

create function discover_submission_private.reserve_discover_submission(
    track_title text, track_artist text, track_source text,
    track_duration integer, track_size bigint, track_sha256 text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    caller uuid := auth.uid();
    reserved_slot integer;
    reviewing_track boolean;
    already_uploaded boolean;
begin
    if caller is null or coalesce(auth.jwt()->'app_metadata'->>'provider', '') <> 'google' then
        return jsonb_build_object('error', 'unauthorized');
    end if;

    perform pg_advisory_xact_lock(770491321935107::bigint);
    select slot, reviewing into reserved_slot, reviewing_track
    from public.discover_submissions
    where owner_id = caller and sha256 = track_sha256;

    if found then
        select exists (
            select 1 from storage.objects
            where bucket_id = 'discover-submissions'
              and name = reserved_slot::text || '.mp3'
              and owner_id = caller::text
              and metadata->>'size' = track_size::text
        ) into already_uploaded;
        if reviewing_track and not already_uploaded then
            return jsonb_build_object('error', 'submission_in_review');
        end if;
        return jsonb_build_object('slot', reserved_slot, 'uploaded', already_uploaded);
    end if;

    if (select count(*) from public.discover_submissions where owner_id = caller) >= 3 then
        return jsonb_build_object('error', 'submission_account_limit');
    end if;

    select candidate into reserved_slot
    from generate_series(1, 30) as slots(candidate)
    where not exists (select 1 from public.discover_submissions where slot = candidate)
      and not exists (
          select 1 from storage.objects
          where bucket_id = 'discover-submissions' and name = candidate::text || '.mp3'
      )
    order by candidate
    limit 1;
    if reserved_slot is null then
        return jsonb_build_object('error', 'submission_queue_full');
    end if;

    insert into public.discover_submissions (owner_id, slot, title, artist, source, duration_ms, byte_size, sha256)
    values (caller, reserved_slot, btrim(track_title), btrim(track_artist), btrim(track_source),
            track_duration, track_size, track_sha256);
    return jsonb_build_object('slot', reserved_slot, 'uploaded', false);
end;
$$;

create function public.reserve_discover_submission(
    track_title text, track_artist text, track_source text,
    track_duration integer, track_size bigint, track_sha256 text
) returns jsonb
language sql security invoker set search_path = '' as $$
    select discover_submission_private.reserve_discover_submission(
        track_title, track_artist, track_source, track_duration, track_size, track_sha256
    );
$$;

revoke all on function discover_submission_private.reserve_discover_submission(text, text, text, integer, bigint, text)
    from public, anon, authenticated;
revoke all on function public.reserve_discover_submission(text, text, text, integer, bigint, text)
    from public, anon, authenticated;
grant usage on schema discover_submission_private to authenticated;
grant execute on function discover_submission_private.reserve_discover_submission(text, text, text, integer, bigint, text)
    to authenticated;
grant execute on function public.reserve_discover_submission(text, text, text, integer, bigint, text)
    to authenticated;
