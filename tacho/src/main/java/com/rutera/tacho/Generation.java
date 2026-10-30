package com.rutera.tacho;

/** Tachograph generation of a file or of a part of it. */
public enum Generation {
    /** First generation, Annex IB (digital tachograph, since 2006). */
    GEN1,
    /** Second generation version 1, Annex IC (smart tachograph, since June 2019). */
    GEN2,
    /** Second generation version 2, Annex IC as amended by Regulation (EU) 2021/1228 (since August 2023). */
    GEN2V2
}
