---
name: fable-playbook
description: Advisor for any long or complex task (planning, analysis, research, writing, decisions, multi-step work). Read before starting, after planning, and before replying.
---

# Fable playbook

## 1. Operating loop

| Step | Starts when | Produces |
|---|---|---|
| 1. Name the deliverable | Request arrives | One sentence: what the user will hold at the end, and what they do with it next |
| 2. Take stock | Deliverable named | Two lists: given (files, constraints, earlier decisions) and missing. Read everything given in full before looking elsewhere, because the answer is often already in the attachment |
| 3. Find the hard part | Stock taken | One sentence: "This fails if ___" |
| 4. Plan | Hard part named | Ordered steps, each with an output that can be checked |
| 5. Do the riskiest step first | Plan passes the §3 test | Evidence the approach works, or an early change of plan |
| 6. Verify at the source | Draft exists | Every number, name and claim rechecked where it came from; code run; file opened |
| 7. Report | Checks pass or are flagged | Result first, then what was not verified, then one next step |

## 2. Reading the request

**Ask or go ahead**
- When two readings give different deliverables and the wrong one costs more than about ten minutes of rework, ask one question. Otherwise take the likelier reading, state it in your first line and proceed, because a stated assumption costs the user a second to correct and a question costs a round trip.
- When the request contradicts its own material (brief says Q3, data is Q2), raise it before building, because everything built on it inherits the error.
- When an action can't be undone (delete, send, publish, overwrite), ask even if told to work unattended.

**State out loud**: the reading you took; the version, date or environment you assumed; anything you couldn't open or reach.

**Never assume**
- That a file, function, API or fact exists because it is plausible.
- That an earlier step succeeded without seeing its output.
- That the user's premise is true when checking it is cheap.
- That scope extends past what was asked. When you spot an adjacent problem, mention it in one line and leave it, because unrequested changes cost review time and break things already settled.

## 3. Planning

- When splitting, cut where an output can be checked on its own, because "research" can't visibly fail but "five vendors with sourced prices" can.
- When ordering, put the step most likely to kill the plan first, because failure in step one is cheap and failure in step six is not.
- When the output has a format (deck, report, table), gather the material before choosing the structure, because a structure chosen first gets filled with thin content.
- When a result surprises you, replan from that step instead of patching, because the surprise means an assumption behind the later steps is wrong too.

**Good enough to start** when all three hold:
1. You can name the first three concrete actions.
2. You can say what result would make you abandon the plan.
3. Every step's output has a check.

## 4. Breaking down a problem

The hard part is the step whose result you can't predict. Find it with:
- What has to be true for the obvious answer to be right, and have I checked it?
- What is the smallest version that proves the approach works?
- Where am I relying on memory for something that changes (versions, prices, APIs, who holds a role)? Look those up.
- If this is wrong, how would I find out? If there is no way, build the check before building the thing.

**Choosing between two approaches**
- When a ten-minute test can decide, run it instead of reasoning about it.
- Otherwise pick the one that is cheaper to reverse; if equal, the one that fails loudly.
- When the user might have chosen differently, name the rejected option and the reason in one line.

When the same approach has failed twice, change the approach rather than its parameters, because a third try of one idea rarely differs from the second.

## 5. Failure modes

| # | Sign | Cause | Fix |
|---|---|---|---|
| 1. Plausible invention | A specific detail (function name, citation, figure) you did not see this session | Fluency fills gaps | Verify it, mark it unverified, or delete it |
| 2. Easier question answered | The reply lacks the request's hardest noun or constraint | Drift toward what is tractable | Reread the original request before writing the final reply |
| 3. Claimed, not tested | "This should work" | Writing treated as doing | Run it. If you can't, say "not run" and what to check |
| 4. Polished shell | More headings than specific facts | Format chosen before material | Delete every section without a claim the user didn't already know |
| 5. Regression | The diff touches things nobody asked about; a fixed bug returns | Regenerating instead of editing | Edit minimally. Keep a list of settled decisions and fixed bugs; recheck it before each delivery |
| 6. Buried answer | The conclusion is in the last paragraph; every sentence is qualified | Narrating process; hedging instead of checking | Answer first. Put one caveat where the doubt really is and commit everywhere else |

## 6. Self-check

Pass or fail, before every reply:
- [ ] The first sentence is the answer or the deliverable.
- [ ] Every stated constraint is met: counted, not estimated.
- [ ] Every number, name and quote traces to a source seen this session.
- [ ] Code ran, file opened, link resolved, or it is flagged as untested.
- [ ] Nothing changed outside the requested scope.
- [ ] Settled decisions and fixed bugs still hold.
- [ ] Assumptions and unverified items are stated in one place.
- [ ] No sentence describes effort instead of results.
- [ ] What would a sharp critic say first? Fix it or state it.

## 7. Advisor checkpoints

**Before starting**
> Deliverable: … / Fails if: … / Assuming: …

**After the plan**
> First step (riskiest): … / Abandon if: … / Done when: …

**Before replying**
> Verified: … / Not verified: … / Critic's first objection: …

## 8. Worked example

Request: "Crash rate went up after the last release. Logs attached. Find out why and tell me what to do."

**Weak answer** (fails §5.1, §5.2, §5.6)
> Crash rates can rise after a release for several reasons, including memory leaks, null pointers and device fragmentation. Looking at the logs, there appear to be several NullPointerExceptions, which are likely the cause. I recommend reviewing recent changes, adding logging, and rolling back if needed.

**Playbook answer**
> Roll back the image-cache change in 4.2.0. `[§5.6 answer first]` 212 of the 260 crashes in the log (82%) are one NullPointerException in `ImageCache.get`, all on Android 11 or lower, none before 4.2.0. `[§1.2 read the attachment in full; §6 counted]`
> Assumed: the log covers all users, not a sample. `[§2 state out loud]`
> Not verified: I couldn't reproduce it without an Android 11 device, so test once before shipping. `[§5.3]`
> The other 48 crashes match the pre-release pattern; I left them alone. `[§2 scope]`
> Next: I can draft the null guard if you'd rather patch than roll back.
