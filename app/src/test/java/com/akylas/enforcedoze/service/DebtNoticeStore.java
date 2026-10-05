package com.akylas.enforcedoze.service;

import com.akylas.enforcedoze.ui.DebtRules;
import java.util.HashSet;
import java.util.Set;

/** Persistence and platform cancellation fake; no Activity or Android runtime. */
public final class DebtNoticeStore implements DebtRules.NoticeGate.Store {
    private Set<String> keys = new HashSet<>();
    // Also represents an existing notification posted by a prior process/version.
    private boolean posted = true;
    public int cancels;
    public int posts;

    public Set<String> load() { return keys; }
    public void save(Set<String> next) { keys = new HashSet<>(next); }
    public boolean posted() { return posted; }
    public void setPosted(boolean value) { posted = value; }
    public void cancel() { cancels++; }
    public boolean post() { posts++; return true; }
}
