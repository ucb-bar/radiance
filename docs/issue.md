Issue Logic
===========

Reservation Station
-------------------

Key features of Muon's reservation stations include:

* **Intra-warp OoO issue**.  Unlike a scoreboard design, the RS looks past a
  blocked head and can issue a later independent instruction *inside a single
  warp.* This allows making progression past a long-stalling memory op and
  uncovering some amount of intra-warp ILP.
* **Operand collection**.  The RS stores the data bits for each register
  operand, not only the busy bits.  This allows the RS to pre-issue operand
  read from the PRF, potentially overlapping PRF access latency with receiving
  forwarded data from the functional units.

A major difference with RS designs in CPU OoO is:

* **No WAR/WAW avoidance via register renaming**.

Operand Fetch
-------------

TODO

Operand Forwarding
------------------

TODO
