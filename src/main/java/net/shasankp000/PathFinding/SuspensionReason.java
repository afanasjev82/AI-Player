package net.shasankp000.PathFinding;

/** Idempotent reasons that temporarily yield navigation's movement ownership. */
public enum SuspensionReason {
    EATING,
    MINING,
    TRADE,
    THREAT,
    COMBAT,
    SHELTER,
    MANUAL_OVERRIDE
}
