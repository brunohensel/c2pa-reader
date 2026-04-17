---
name: do-work
description: "Execute a unit of work end-to-end: plan, implement, validate with ./gradlew detekt and tests,
 then generate a commit message (but do not commit yourself). Use when user wants to do work, build a feature, fix a bug, or implement a phase from a plan."
---

# Do Work

Execute a complete unit of work: plan it, build it, validate it, commit it.

## Workflow

### 1. Understand the task
Read any referenced plan or PRD. Explore the codebase to understand the relevant files, patterns, and conventions.
If the task is ambiguous, ask the user to clarify scope before proceeding.

### 2. Plan the implementation (optional)

If the task has not already been planned, create a plan for it.

### 3. Implement
**For data layer or viewmodel code**: use red/green/refactor, one test at a time in a tracer-bullet style.
1. Write a single failing test for the smallest vertical slice of behavior
2. Run the test — confirm it fails (red)
3. Write the minimum code to make it pass (green)
4. Repeat from step 1 for the next slice of behavior
5. Refactor if needed while keeping tests green

Each test should target one thin vertical slice through the system.
**Do not write all tests upfront** — write one, make it pass, then move to the next.

**For frontend code**: implement directly without TDD. Then verify if it's worth writing composable tests for the new code.

### 4. Validate

Run the feedback loops and fix any issues. Repeat until both pass cleanly.

### 5. Commit

Tests pass, commit the work been done.
For the commit message, follow the [Conventional Commits format](reference/conventional-commit.md))
