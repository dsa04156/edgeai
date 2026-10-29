package io.edgeai.domain.resourceprofile;

/** Why an observation is or is not usable; absent data is a status, never a zero value. */
public enum ObservationStatus { AVAILABLE, NO_DATA, STALE, INSUFFICIENT, UNSUPPORTED, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }
