package com.nxtshift.app.domain;

public enum DutyStatus {
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    OFF("Weekly Off"),
    LEAVE("On Leave");

    private final String label;

    DutyStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
