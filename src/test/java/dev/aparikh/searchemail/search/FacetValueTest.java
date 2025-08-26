package dev.aparikh.searchemail.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FacetValueTest {

    @Test
    void facetValueCreatesCorrectly() {
        FacetValue value = new FacetValue("alice@example.com", 5L);

        assertThat(value.value()).isEqualTo("alice@example.com");
        assertThat(value.count()).isEqualTo(5L);
    }

    @Test
    void facetValueWithZeroCount() {
        FacetValue value = new FacetValue("bob@example.com", 0L);

        assertThat(value.value()).isEqualTo("bob@example.com");
        assertThat(value.count()).isZero();
    }

    @Test
    void facetValueEquality() {
        FacetValue value1 = new FacetValue("alice@example.com", 5L);
        FacetValue value2 = new FacetValue("alice@example.com", 5L);
        FacetValue value3 = new FacetValue("bob@example.com", 5L);

        assertThat(value1).isEqualTo(value2);
        assertThat(value1).isNotEqualTo(value3);
    }
}