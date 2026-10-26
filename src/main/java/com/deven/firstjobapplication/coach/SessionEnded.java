package com.deven.firstjobapplication.coach;

/**
 * Published when someone's session is over (sign-out, a new sign-in, expiry, or a guest going idle). Everything they created
 * during it (jobs, assessments, scan history, watches) is deleted: ProofPath keeps no history between sessions.
 * login is "github-name", "email:address" or "guest:id".
 */
public record SessionEnded(String login) { }
