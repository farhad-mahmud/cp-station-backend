-- ============================================================================
-- CP-STATION :: Virtual Classroom (paid cohort courses)
--
-- Reuses the existing `mentors` table as the instructor identity, so a trainer
-- who already takes mock interviews can also run a course with no second
-- profile and no second approval.
--
-- Safe to re-run: every statement is guarded.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A course is a cohort: fixed start date, fixed length, capped seats, one
-- recurring class link. price_bdt is the whole-course fee, not per session.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS courses (
    id             SERIAL PRIMARY KEY,
    mentor_id      INTEGER NOT NULL REFERENCES mentors(id) ON DELETE CASCADE,
    title          TEXT NOT NULL,
    summary        TEXT,
    description    TEXT,
    stack          TEXT NOT NULL,
    level          TEXT NOT NULL DEFAULT 'beginner',
    price_bdt      INTEGER NOT NULL,
    duration_weeks INTEGER NOT NULL DEFAULT 4,
    total_hours    INTEGER,
    seat_limit     INTEGER NOT NULL DEFAULT 20,
    enrolled_count INTEGER NOT NULL DEFAULT 0,
    start_date     DATE,
    schedule_note  TEXT,
    meeting_link   TEXT,
    cover_url      TEXT,
    status         TEXT NOT NULL DEFAULT 'draft',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- status: draft | pending | published | archived
--   draft     — mentor is still writing it, invisible to everyone else
--   pending   — submitted for admin review
--   published — publicly enrollable
--   archived  — hidden from the catalogue, existing enrollments untouched
-- level: beginner | intermediate | advanced

CREATE INDEX IF NOT EXISTS idx_courses_public ON courses (status, start_date);
CREATE INDEX IF NOT EXISTS idx_courses_mentor ON courses (mentor_id, status);
CREATE INDEX IF NOT EXISTS idx_courses_stack  ON courses (stack);

-- enrolled_count is a counter rather than a COUNT(*) so a seat can be claimed
-- with one atomic conditional UPDATE. Never let it exceed the cap.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'courses_seats_chk'
    ) THEN
        ALTER TABLE courses
            ADD CONSTRAINT courses_seats_chk
            CHECK (enrolled_count >= 0 AND enrolled_count <= seat_limit);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Ordered syllabus. `covered_at` is set by the mentor as the cohort progresses,
-- which gives every enrolled student a shared, honest view of where the class is.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_syllabus (
    id         SERIAL PRIMARY KEY,
    course_id  INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    position   INTEGER NOT NULL,
    title      TEXT NOT NULL,
    detail     TEXT,
    est_hours  NUMERIC(4,1),
    covered_at TIMESTAMPTZ,
    UNIQUE (course_id, position)
);

CREATE INDEX IF NOT EXISTS idx_syllabus_course ON course_syllabus (course_id, position);

-- ---------------------------------------------------------------------------
-- Enrollment. amount_bdt and platform_fee_bdt are frozen at enrollment time so
-- a later price change never rewrites what a student paid or a mentor is owed.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS enrollments (
    id               SERIAL PRIMARY KEY,
    course_id        INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount_bdt       INTEGER NOT NULL,
    platform_fee_bdt INTEGER NOT NULL DEFAULT 0,
    status           TEXT NOT NULL DEFAULT 'pending_payment',
    hold_expires_at  TIMESTAMPTZ,
    goal             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- status: pending_payment | payment_review | active | completed
--       | cancelled | expired

-- A student may hold at most one *live* enrollment per course. Partial on
-- purpose: after cancelling or expiring they must be able to enroll again.
CREATE UNIQUE INDEX IF NOT EXISTS idx_enrollments_live
    ON enrollments (course_id, student_id)
    WHERE status IN ('pending_payment', 'payment_review', 'active', 'completed');

CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments (student_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_enrollments_course  ON enrollments (course_id, status);
CREATE INDEX IF NOT EXISTS idx_enrollments_hold    ON enrollments (status, hold_expires_at);

-- ---------------------------------------------------------------------------
-- Payments: one table, two kinds of purchase.
--
-- Rather than a second payments table, `payments` now points at either a
-- booking or an enrollment. The admin verification queue, the idempotency key
-- and the bKash TrxID uniqueness rule are then shared by both products —
-- one place where money is confirmed, not two.
-- ---------------------------------------------------------------------------
ALTER TABLE payments ALTER COLUMN booking_id DROP NOT NULL;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS enrollment_id INTEGER
    REFERENCES enrollments(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_payments_enrollment ON payments (enrollment_id);

-- Exactly one target, never both, never neither.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'payments_target_chk'
    ) THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_target_chk
            CHECK ((booking_id IS NOT NULL) <> (enrollment_id IS NOT NULL));
    END IF;
END $$;
