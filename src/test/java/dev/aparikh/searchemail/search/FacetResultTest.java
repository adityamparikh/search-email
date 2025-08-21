package dev.aparikh.searchemail.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FacetResultTest {

    @Test
    void facetResultCreatesCorrectly() {
        List<FacetValue> values = List.of(
                new FacetValue("alice@example.com", 5L),
                new FacetValue("bob@example.com", 3L)
        );

        FacetResult result = new FacetResult("from_addr", values);

        assertThat(result.field()).isEqualTo("from_addr");
        assertThat(result.values()).isEqualTo(values);
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void facetResultWithEmptyValues() {
        FacetResult result = new FacetResult("from_addr", List.of());

        assertThat(result.field()).isEqualTo("from_addr");
        assertThat(result.values()).isEmpty();
    }
}