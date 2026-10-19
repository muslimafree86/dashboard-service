package com.muslimafree.dashboard.member;

/** Same values as user-profile-managment ({@code user_profiles.gender}). */
public enum Gender {
    MALE,
    FEMALE;

    public Gender opposite() {
        return this == MALE ? FEMALE : MALE;
    }
}
