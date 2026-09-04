package com.gtacore;

/**
 * High-level states for a police unit.
 *
 * The first driving tests only use GTACore's manual vehicle controls,
 * but the full dispatch system will move PoliceUnit instances through
 * these states.
 */
public enum PoliceState {
    PATROL,
    RESPONDING,
    VEHICLE_PURSUIT,
    STOPPING_TO_EXIT,
    EXIT_VEHICLE,
    FOOT_PURSUIT,
    ARREST,
    RETURN_TO_CAR,
    RETURN_TO_STATION
}
