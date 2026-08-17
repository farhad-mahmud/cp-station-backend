-- ============================================================================
-- CP-STATION :: seed one published demo course
--
-- Attaches a cohort to mentor id 2 (Shahriar Kabir), whose stacks already
-- include "DSA & Problem Solving", so the course matches the instructor's
-- declared expertise rather than inventing a new one.
--
-- Published immediately so the /classroom page has real content. Archive it
-- from the admin console, or edit it from /mentor/courses, whenever you like.
--
-- Safe to re-run: guarded on the course title.
-- ============================================================================

INSERT INTO courses (
    mentor_id, title, summary, description, stack, level, price_bdt,
    duration_weeks, total_hours, seat_limit, start_date, schedule_note,
    meeting_link, status
)
SELECT
    2,
    'Problem Solving Bootcamp: Contest to Interview',
    'Six weeks of guided practice that takes you from solving Div 2 A/B to clearing DSA interview rounds.',
    'A live, cohort-based bootcamp for students who can already write basic C++ or Java but stall on '
      || 'anything harder than an easy problem.' || chr(10) || chr(10)
      || 'Every week has one topic, a set of curated problems, and a live session where we solve them '
      || 'together on a shared screen — including the part nobody shows you: how to read a problem, '
      || 'find the invariant, and decide on an approach before writing code.' || chr(10) || chr(10)
      || 'You will be asked to attempt the week''s problems before each class. Come prepared and this '
      || 'moves fast.',
    'DSA & Problem Solving',
    'intermediate',
    2500,
    6,
    18,
    20,
    (CURRENT_DATE + 14),
    'Fri & Sun, 9:00–10:30 PM (Dhaka)',
    NULL,
    'published'
WHERE NOT EXISTS (
    SELECT 1 FROM courses WHERE title = 'Problem Solving Bootcamp: Contest to Interview'
);

-- Syllabus: six modules, one per week, 3 hours of live teaching each.
INSERT INTO course_syllabus (course_id, position, title, detail, est_hours)
SELECT c.id, v.position, v.title, v.detail, v.est_hours
  FROM courses c
  CROSS JOIN (
      VALUES
        (1, 'Complexity, and reading a problem properly',
            'Estimating from the constraints, spotting the intended complexity before you code, and the habits that stop wrong-answer spirals.', 3.0),
        (2, 'Two pointers, sliding window, prefix sums',
            'The patterns behind most easy-to-medium array problems, and how to recognise which one a statement is asking for.', 3.0),
        (3, 'Sorting, binary search, and binary search on the answer',
            'Including the monotonic-predicate trick that turns many optimisation problems into a search.', 3.0),
        (4, 'Graphs: BFS, DFS, and shortest paths',
            'Grid and adjacency representations, connected components, Dijkstra and 0-1 BFS.', 3.0),
        (5, 'Dynamic programming that actually makes sense',
            'Choosing state, writing the recurrence before the code, and converting to bottom-up. Knapsack, LIS, interval DP.', 3.0),
        (6, 'Interview simulation and post-mortem',
            'A timed mock round each, then a group review of where solutions went wrong and why.', 3.0)
  ) AS v(position, title, detail, est_hours)
 WHERE c.title = 'Problem Solving Bootcamp: Contest to Interview'
ON CONFLICT (course_id, position) DO NOTHING;
