\* SPDX-FileCopyrightText: Epistola Nederland B.V.
\*
\* SPDX-License-Identifier: AGPL-3.0-only

--------------------------- MODULE CatalogConcurrency ---------------------------
(***************************************************************************)
(* Catalog commands that run at the same time, as PostgreSQL runs them:    *)
(* READ COMMITTED transactions, each statement reading what is committed   *)
(* when it starts, with the row locks a foreign key and a DELETE take.     *)
(*                                                                         *)
(* Modelled, from the code on release/2.0:                                 *)
(*   - Declare: a draft declares another catalog, naming its draft or      *)
(*     pinning its highest release (CatalogDependencyStore.replace, which  *)
(*     checks for a cycle, requireNoCycle, before it writes).              *)
(*   - Release: a draft becomes a release (ReleaseCatalogDraft). A         *)
(*     declaration naming a draft with nothing to release pins that       *)
(*     draft's base; one pinning nothing pins its catalog's highest        *)
(*     release; one naming a draft with changes refuses the release. The   *)
(*     declarations of other drafts naming the released draft then pin     *)
(*     the release (pinDeclarationsOf).                                    *)
(*   - DeleteRelease: refused while a declaration pins the release         *)
(*     (ReleaseInUse.requireUnused), then the row is deleted. A            *)
(*     declaration's target_version_id is ON DELETE SET NULL.              *)
(*                                                                         *)
(* Locks: inserting a row whose foreign key names a version takes KEY      *)
(* SHARE on that version's row; a DELETE takes FOR UPDATE, which waits for *)
(* KEY SHARE holders and makes their foreign-key check fail once it        *)
(* commits. A check that ran before such a wait is not run again.          *)
(*                                                                         *)
(* Fixed = FALSE is the code as it is; Fixed = TRUE adds the two locks the *)
(* fix takes: a tenant-wide advisory lock before the cycle check, and the  *)
(* release's row locked FOR UPDATE before the in-use check.                *)
(*                                                                         *)
(* The invariants are the SQL rules of CatalogInvariants with the same     *)
(* names: catalogs-never-use-each-other, release-pins-releases and         *)
(* pinned-release-present.                                                 *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
    Cats,       \* catalog keys
    Procs,      \* concurrent sessions
    MaxCmds,    \* commands per session
    Unchanged,  \* catalogs whose next draft has nothing of its own to release
    Fixed,      \* TRUE: the fix's locks
    None,
    Refuse      \* what a declaration the release cannot pin becomes

ASSUME Unchanged \subseteq Cats /\ MaxCmds \in Nat /\ Fixed \in BOOLEAN

\* Each catalog starts with one release, the base of its next draft.
Rel(c)   == <<c, "r0">>
Draft(c) == <<c, "next">>
Vers     == {Rel(c) : c \in Cats} \cup {Draft(c) : c \in Cats}
Cat(v)   == v[1]

\* A row of catalog_version_dependencies: the version declaring, the catalog it names, the version
\* row it targets (target_version_id) and the release it pins (release_version), None for none.
Decl == [from : Vers, to : Cats, target : Vers \cup {None}, pin : Vers \cup {None}]

VARIABLES
    exists,     \* committed: the version's row is there
    state,      \* committed: "draft" or "released"
    decls,      \* committed rows of catalog_version_dependencies
    pc,         \* per session: "idle", "refused", "write", "commit"
    cmd,        \* per session: the command it runs
    plan,       \* per session: what its check decided, and what it staged
    count,      \* per session: commands run
    keyShare,   \* version -> sessions holding KEY SHARE (a foreign key naming it)
    forUpdate,  \* version -> the session holding FOR UPDATE (its DELETE), or None
    advisory    \* the tenant's dependency lock (Fixed only): its holder, or None

vars == <<exists, state, decls, pc, cmd, plan, count, keyShare, forUpdate, advisory>>

-----------------------------------------------------------------------------
(* Reading what is committed *)

Live(v) == exists[v]
Released(v) == exists[v] /\ state[v] = "released"
IsDraft(v) == exists[v] /\ state[v] = "draft"

\* The catalog's highest release: its released next draft above its first release.
Highest(c) == IF Released(Draft(c)) THEN Draft(c) ELSE IF Released(Rel(c)) THEN Rel(c) ELSE None

\* Catalog c uses catalog t: some version of c declares t (the rule judges every version).
Uses(D) == {<<Cat(d.from), d.to>> : d \in D}

RECURSIVE Closure(_, _)
Closure(E, n) ==
    IF n = 0 THEN E
    ELSE Closure(E \cup {<<x[1], y[2]>> : x \in E, y \in {e \in E : e[1] = x[2]}}, n - 1)

Acyclic(E) == \A c \in Cats : <<c, c>> \notin Closure(E, Cardinality(Cats))

-----------------------------------------------------------------------------
(* Locks *)

FreeOfOthers(v, p) == forUpdate[v] \in {None, p}
NoSharersBut(v, p) == keyShare[v] \subseteq {p}

ReleaseLocks(p) ==
    /\ keyShare' = [v \in Vers |-> keyShare[v] \ {p}]
    /\ forUpdate' = [v \in Vers |-> IF forUpdate[v] = p THEN None ELSE forUpdate[v]]
    /\ advisory' = IF advisory = p THEN None ELSE advisory

Finish(p) ==
    /\ pc' = [pc EXCEPT ![p] = "idle"]
    /\ plan' = [plan EXCEPT ![p] = None]
    /\ ReleaseLocks(p)

-----------------------------------------------------------------------------
(* The commands a session may start *)

Commands ==
    [kind : {"declareDraft", "declarePin"}, from : Cats, to : Cats]
    \cup [kind : {"release"}, cat : Cats]
    \cup [kind : {"deleteRelease"}, v : Vers]

Init ==
    /\ exists = [v \in Vers |-> TRUE]
    /\ state = [v \in Vers |-> IF v[2] = "r0" THEN "released" ELSE "draft"]
    /\ decls = {}
    /\ pc = [p \in Procs |-> "idle"]
    /\ cmd = [p \in Procs |-> None]
    /\ plan = [p \in Procs |-> None]
    /\ count = [p \in Procs |-> 0]
    /\ keyShare = [v \in Vers |-> {}]
    /\ forUpdate = [v \in Vers |-> None]
    /\ advisory = None

-----------------------------------------------------------------------------
(* Declare: requireNoCycle reads, then the rows are written. *)

DeclareCheck(p, c) ==
    LET d == cmd[p]
        others == {x \in decls : ~(x.from = Draft(c) /\ x.to = d.to)}
        added == <<c, d.to>>
        pinTo == Highest(d.to)
    IN
    /\ (Fixed => advisory \in {None, p})
    /\ advisory' = IF Fixed THEN p ELSE advisory
    /\ IF /\ d.to # c
          /\ IsDraft(Draft(c))
          /\ Acyclic(Uses(others) \cup {added})
          /\ (d.kind = "declarePin" => pinTo # None)
          /\ (d.kind = "declareDraft" => IsDraft(Draft(d.to)))
       THEN /\ pc' = [pc EXCEPT ![p] = "write"]
            /\ plan' = [plan EXCEPT ![p] = [pin |-> IF d.kind = "declarePin" THEN pinTo ELSE None]]
            /\ UNCHANGED <<keyShare, forUpdate>>
       ELSE \* refused: the transaction rolls back, letting go of what it locked (Done)
            /\ pc' = [pc EXCEPT ![p] = "refused"]
            /\ UNCHANGED <<plan, keyShare, forUpdate>>

\* The INSERT: TARGET resolves against what is committed, then the foreign key locks the target.
DeclareWrite(p, c) ==
    LET d == cmd[p]
        target == IF d.kind = "declareDraft"
                  THEN IF IsDraft(Draft(d.to)) THEN Draft(d.to) ELSE None
                  ELSE IF Released(plan[p].pin) THEN plan[p].pin ELSE None
        row == [from |-> Draft(c), to |-> d.to, target |-> target, pin |-> plan[p].pin]
    IN
    /\ target # None => FreeOfOthers(target, p)
    /\ keyShare' = IF target = None THEN keyShare ELSE [keyShare EXCEPT ![target] = @ \cup {p}]
    /\ plan' = [plan EXCEPT ![p] = [pin |-> plan[p].pin, row |-> row]]
    /\ pc' = [pc EXCEPT ![p] = "commit"]
    /\ UNCHANGED <<forUpdate, advisory>>

DeclareCommit(p, c) ==
    LET row == plan[p].row IN
    \* A target deleted since the foreign key waited on it: the INSERT fails, the transaction rolls back.
    IF row.target # None /\ ~Live(row.target)
    THEN /\ Finish(p) /\ UNCHANGED <<decls, exists, state>>
    ELSE /\ decls' = {x \in decls : ~(x.from = row.from /\ x.to = row.to)} \cup {row}
         /\ Finish(p)
         /\ UNCHANGED <<exists, state>>

-----------------------------------------------------------------------------
(* Release: the draft's declarations are pinned, then the draft is frozen as the release. *)

\* What one declaration of the draft becomes, or "refuse".
PinOf(x) ==
    IF x.pin # None
    THEN IF Released(x.pin) THEN x.pin ELSE Refuse
    ELSE IF x.target # None /\ IsDraft(x.target)
         THEN IF Cat(x.target) \in Unchanged /\ Released(Rel(Cat(x.target))) THEN Rel(Cat(x.target)) ELSE Refuse
         ELSE IF Highest(x.to) # None THEN Highest(x.to) ELSE Refuse

ReleaseCheck(p, c) ==
    LET own == {x \in decls : x.from = Draft(c)}
    IN
    /\ IF IsDraft(Draft(c)) /\ \A x \in own : PinOf(x) # Refuse
       THEN LET pinned == {[x EXCEPT !.pin = PinOf(x), !.target = PinOf(x)] : x \in own}
                \* replace() deletes and inserts each changed row: those take KEY SHARE on their pin.
                inserted == {x \in pinned : x \notin own}
            IN /\ pc' = [pc EXCEPT ![p] = "write"]
               /\ plan' = [plan EXCEPT ![p] = [own |-> own, pinned |-> pinned, inserted |-> inserted]]
       ELSE /\ pc' = [pc EXCEPT ![p] = "refused"]
            /\ UNCHANGED plan
    /\ UNCHANGED <<keyShare, forUpdate, advisory>>

ReleaseWrite(p, c) ==
    LET locks == {x.target : x \in plan[p].inserted}
        \* pinDeclarationsOf: the rows committed now that name this draft.
        repin == {x \in decls : x.target = Draft(c) /\ x.pin = None /\ x.from # Draft(c)}
    IN
    /\ \A v \in locks : FreeOfOthers(v, p)
    /\ keyShare' = [v \in Vers |-> IF v \in locks THEN keyShare[v] \cup {p} ELSE keyShare[v]]
    /\ plan' = [plan EXCEPT ![p] = [own |-> plan[p].own, pinned |-> plan[p].pinned, inserted |-> plan[p].inserted, repin |-> repin]]
    /\ pc' = [pc EXCEPT ![p] = "commit"]
    /\ UNCHANGED <<forUpdate, advisory>>

ReleaseCommit(p, c) ==
    LET pl == plan[p] IN
    IF \E x \in pl.inserted : ~Live(x.target)
    THEN /\ Finish(p) /\ UNCHANGED <<decls, exists, state>>
    ELSE /\ state' = [state EXCEPT ![Draft(c)] = "released"]
         /\ decls' = {IF x \in pl.repin THEN [x EXCEPT !.pin = Draft(c)] ELSE x : x \in (decls \ pl.own)} \cup pl.pinned
         /\ Finish(p)
         /\ UNCHANGED exists

-----------------------------------------------------------------------------
(* DeleteRelease: requireUnused reads, then the DELETE locks the row. *)

DeleteCheck(p, v) ==
    /\ (Fixed => FreeOfOthers(v, p) /\ NoSharersBut(v, p))
    /\ forUpdate' = IF Fixed THEN [forUpdate EXCEPT ![v] = p] ELSE forUpdate
    /\ IF Released(v) /\ ~\E x \in decls : x.pin = v
       THEN /\ pc' = [pc EXCEPT ![p] = "write"]
            /\ plan' = [plan EXCEPT ![p] = [v |-> v]]
       ELSE /\ pc' = [pc EXCEPT ![p] = "refused"]
            /\ UNCHANGED plan
    /\ UNCHANGED <<keyShare, advisory>>

DeleteWrite(p, v) ==
    /\ FreeOfOthers(v, p) /\ NoSharersBut(v, p)
    /\ forUpdate' = [forUpdate EXCEPT ![v] = p]
    /\ pc' = [pc EXCEPT ![p] = "commit"]
    /\ UNCHANGED <<keyShare, plan, advisory>>

DeleteCommit(p, v) ==
    /\ exists' = [exists EXCEPT ![v] = FALSE]
    \* Its own declarations cascade; another's target is set null, its pin stays.
    /\ decls' = {IF x.target = v THEN [x EXCEPT !.target = None] ELSE x : x \in {y \in decls : y.from # v}}
    /\ Finish(p)
    /\ UNCHANGED state

-----------------------------------------------------------------------------

Start(p) ==
    /\ pc[p] = "idle" /\ cmd[p] = None /\ count[p] < MaxCmds
    /\ \E c \in Commands :
        /\ cmd' = [cmd EXCEPT ![p] = c]
        /\ count' = [count EXCEPT ![p] = @ + 1]
    /\ UNCHANGED <<exists, state, decls, pc, plan, keyShare, forUpdate, advisory>>

\* A session its check refused rolls back, letting go of what it locked.
Done(p) ==
    /\ pc[p] = "refused"
    /\ pc' = [pc EXCEPT ![p] = "idle"]
    /\ cmd' = [cmd EXCEPT ![p] = None]
    /\ ReleaseLocks(p)
    /\ UNCHANGED <<exists, state, decls, plan, count>>

Check(p) ==
    /\ pc[p] = "idle" /\ cmd[p] # None
    /\ LET k == cmd[p].kind IN
       CASE k \in {"declareDraft", "declarePin"} -> DeclareCheck(p, cmd[p].from)
         [] k = "release" -> ReleaseCheck(p, cmd[p].cat)
         [] k = "deleteRelease" -> DeleteCheck(p, cmd[p].v)
    /\ UNCHANGED <<exists, state, decls, cmd, count>>

Write(p) ==
    /\ pc[p] = "write"
    /\ LET k == cmd[p].kind IN
       CASE k \in {"declareDraft", "declarePin"} -> DeclareWrite(p, cmd[p].from)
         [] k = "release" -> ReleaseWrite(p, cmd[p].cat)
         [] k = "deleteRelease" -> DeleteWrite(p, cmd[p].v)
    /\ UNCHANGED <<exists, state, decls, cmd, count>>

Commit(p) ==
    /\ pc[p] = "commit"
    /\ LET k == cmd[p].kind IN
       CASE k \in {"declareDraft", "declarePin"} -> DeclareCommit(p, cmd[p].from)
         [] k = "release" -> ReleaseCommit(p, cmd[p].cat)
         [] k = "deleteRelease" -> DeleteCommit(p, cmd[p].v)
    /\ cmd' = [cmd EXCEPT ![p] = None]
    /\ UNCHANGED count

\* Every session has run its commands: nothing left to do.
Finished ==
    /\ \A p \in Procs : pc[p] = "idle" /\ cmd[p] = None /\ count[p] = MaxCmds
    /\ UNCHANGED vars

Next == Finished \/ \E p \in Procs : Start(p) \/ Done(p) \/ Check(p) \/ Write(p) \/ Commit(p)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Invariants: CatalogInvariants' rules of the same names *)

TypeOK ==
    /\ decls \subseteq Decl
    /\ pc \in [Procs -> {"idle", "refused", "write", "commit"}]

\* catalogs-never-use-each-other
NeverUseEachOther == Acyclic(Uses(decls))

\* release-pins-releases: a release's declarations all pin a release.
ReleasePinsReleases == \A x \in decls : Released(x.from) => x.pin # None

\* pinned-release-present: the release a release pins is here.
PinnedReleasePresent == \A x \in decls : Released(x.from) /\ x.pin # None => Released(x.pin)
================================================================================
