package com.deven.firstjobapplication.coach;

/**
 * A guest signed in or signed up: the jobs they had read as a guest move to their new account instead of being deleted,
 * so a guest who reads a posting and then creates an account does not have to start over.
 */
public record SessionAdopted(String fromGuest, String toLogin) { }
