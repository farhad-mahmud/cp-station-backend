-- ============================================================================
-- CP-STATION :: seed the first mentor
--
-- Attaches an approved mentor profile to user id 1 (rixonahmed@gmail.com) and
-- publishes three evening slots so the Mock Interview browse page has real
-- content on first render.
--
-- headline, bio, company and photo_url are deliberately left NULL: those are
-- public claims about a real person and belong to the account owner, who can
-- fill them in from the mentor dashboard. Only display_name is set, and it is
-- derived from the account email.
--
-- Safe to re-run: every insert is guarded.
-- ============================================================================

INSERT INTO mentors (user_id, display_name, hourly_rate_bdt, status)
SELECT 1, 'Rixon Ahmed', 500, 'approved'
WHERE NOT EXISTS (SELECT 1 FROM mentors WHERE user_id = 1);

INSERT INTO mentor_stacks (mentor_id, stack)
SELECT m.id, 'DSA & Problem Solving' FROM mentors m WHERE m.user_id = 1
ON CONFLICT (mentor_id, stack) DO NOTHING;

-- Three 30-minute slots at 20:00, 21:00 and 22:00 Asia/Dhaka (UTC+6) on the
-- next three days, so they read as evening sessions to a Bangladeshi student.
INSERT INTO mentor_slots (mentor_id, start_at, end_at, status)
SELECT m.id, slot.start_at, slot.start_at + interval '30 minutes', 'open'
  FROM mentors m
  CROSS JOIN (
      VALUES
        ((CURRENT_DATE + 1)::timestamptz + interval '14 hours'),
        ((CURRENT_DATE + 2)::timestamptz + interval '15 hours'),
        ((CURRENT_DATE + 3)::timestamptz + interval '16 hours')
  ) AS slot(start_at)
 WHERE m.user_id = 1
ON CONFLICT (mentor_id, start_at) DO NOTHING;
