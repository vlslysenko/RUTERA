package com.rutera.core.calc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which roads charge a truck toll per km. */
class TollNetworkTest {

    @Test
    void networksAndFlags() {
        assertThat(TollNetwork.tolled("PL", "motorway", false)).isTrue();
        assertThat(TollNetwork.tolled("PL", "trunk", false)).isTrue();
        assertThat(TollNetwork.tolled("PL", "primary", false)).isFalse();
        assertThat(TollNetwork.tolled("DE", "primary", false)).as("federal roads").isTrue();
        assertThat(TollNetwork.tolled("DE", "secondary", false)).isFalse();
        assertThat(TollNetwork.tolled("FR", "motorway", false)).as("only flagged concessions").isFalse();
        assertThat(TollNetwork.tolled("FR", "motorway", true)).isTrue();
        assertThat(TollNetwork.tolled("CH", "residential", false)).as("LSVA on every road").isTrue();
        assertThat(TollNetwork.tolled("LT", "motorway", false)).as("vignette, no km toll").isFalse();
        assertThat(TollNetwork.tolled("AT", null, false)).isFalse();
    }
}
