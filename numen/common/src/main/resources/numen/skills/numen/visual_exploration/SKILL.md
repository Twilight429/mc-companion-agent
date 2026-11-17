---
name: visual_exploration
description: Use explore for explicitly requested exploration, unknown targets or a blocked route requiring new directions. Fresh visual capture, native reachability and bounded movement. 探索、未知目标、寻找方向。
---

Call `explore` only for an explicit exploration request, an unknown required target, or a blocked task needing a new route.
Extract the resource from the original task: goal water, wood, leaves, ore or constructed. Do not guess an unrequested resource.
Use mode locate for native-confirmed target coordinates, reach to move near the target, or frontier_exploration with goal any/terrain
for an explicit search of unknown areas. A candidate is a hypothesis; native block queries confirm target types and coordinates.
One call is a bounded SESSION containing multiple fresh observations, native-validated distant waypoints and native moves.
Set max_distance (actual cumulative blocks, default 96, 4..256), max_duration (whole session seconds, default 45, 5..120),
and max_waypoints (movement attempts, default 4, 1..12) to fit the task. Do not call the LLM or explore again for every waypoint.
The tool internally replans at waypoint arrival or after an invalid path, avoids revisiting regions, and stops at a budget or goal.
The `<exploration>` runtime fragment declares NORMAL_TASK, EXPLORATION or REPLANNING and current WORLD coordinates.
NORMAL_TASK: ordinary known executable targets, scans, mining and interactions do not need explore.
EXPLORATION: the whole session is active, including internal movement and re-observation. Do not issue competing body actions.
REPLANNING: read termination_reason, goal_found/goal_reached, goal_position, actual travel, waypoint_history and execution_result.
Resume the ORIGINAL task using confirmed resource coordinates; finding water or wood does not collect it. Budget exhaustion with
progress is partial exploration, not target discovery. Do not blindly restart a failed or exhausted session.
Goal finish explicitly restores NORMAL_TASK without a camera request. A normal native body task also resumes ordinary work.
camera_unavailable, stale_frame, inference_failed, danger, no_progress and no_executable_candidate require upper-level replanning.
Positions are absolute world coordinates. Visual directions are hypotheses, not proof of a resource or a globally unreachable region.
Native planning may dig or build subject to the owner's permissions. Its execution receipt reports actual edits and failure.
The native spatial memory contains native scan facts only; visual predictions do not update it. Do not send image data to the LLM.
