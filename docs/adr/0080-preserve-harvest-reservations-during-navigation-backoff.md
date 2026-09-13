# ADR 0080: Preserve acquired harvest reservations during navigation backoff

Status: focused regression and grouped W verified. 2026-09-13.

The V native campaign found two workers removing two ore blocks each but collecting
three and one drops respectively. Total physical items remained conserved, while one
worker could no longer satisfy its delivery quota. The generic task release path
removed its acquired work reservation when navigation entered a finite retry wait.
A neighbor could then reserve the same area and collect the waiting worker's drop.

TaskService now distinguishes automatic WAITING from explicit pause or termination.
It still releases transient controls, paths, planning and container-step reservations.
An already acquired harvest claim for the same task may remain until the retry deadline
plus its normal lease. No pending request is granted or refreshed by this operation.
Expired claims and different task identities cannot be revived. Repeated release
callbacks use the existing remaining wait and clock; they do not grant fresh task time,
attempts, stock or an indefinite reservation. Retry waits remain bounded to 200 ticks.

Pause, cancellation, interruption, removal, unload and runtime teardown retain their
release paths. The reservation is transient and requires ordinary renewal after retry;
it does not introduce a persistence field, new executor, mining-specific policy or
item/entity ownership emulation. Core pickup permissions remain authoritative.

Evidence: shared-mining-v4-reproducer.log failed both forced 20/200-tick collection
backoffs at the exact lost-reservation assertion. After the fix, eight required native
cases passed: six independent fresh worker pairs and both forced backoffs. Each retained
the original real removal, per-worker quota, chest total, initial inventory, return,
control cleanup and task-codec assertions. Eight kernel tests cover finite expiry,
repeated release, incidental pickup exclusion, task identity and explicit release.
Full grouped campaign W passed; standalone publication validation is separate.

W evidence: p11-assignment-w-evidence.json, source194d8c83eb5de5a993c6d9a833e618c5ba19d9db278ecd47b3fde6ba666bd63a; 363 units,206 native,24 separate-JVM checkpoints,19 lifecycle,12 actual client cases and three-mod loading passed.
