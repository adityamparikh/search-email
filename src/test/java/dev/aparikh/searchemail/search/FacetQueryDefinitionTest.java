package dev.aparikh.searchemail.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacetQueryDefinitionTest {

    @Test
    void facetQueryDefinitionCreatesCorrectly() {
        FacetQueryDefinition facetQuery = new FacetQueryDefinition("External Emails", "NOT from_addr:*@acme.com");
        
        assertThat(facetQuery.label()).isEqualTo("External Emails");
        assertThat(facetQuery.query()).isEqualTo("NOT from_addr:*@acme.com");
    }

    @Test
    void facetQueryDefinitionRejectsNullLabel() {
        assertThatThrownBy(() -> new FacetQueryDefinition(null, "subject:meeting"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query label cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionRejectsBlankLabel() {
        assertThatThrownBy(() -> new FacetQueryDefinition("   ", "subject:meeting"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query label cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionRejectsEmptyLabel() {
        assertThatThrownBy(() -> new FacetQueryDefinition("", "subject:meeting"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query label cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionRejectsNullQuery() {
        assertThatThrownBy(() -> new FacetQueryDefinition("Valid Label", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionRejectsBlankQuery() {
        assertThatThrownBy(() -> new FacetQueryDefinition("Valid Label", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionRejectsEmptyQuery() {
        assertThatThrownBy(() -> new FacetQueryDefinition("Valid Label", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Facet query cannot be null or blank");
    }

    @Test
    void facetQueryDefinitionEquality() {
        FacetQueryDefinition facet1 = new FacetQueryDefinition("External Emails", "NOT from_addr:*@acme.com");
        FacetQueryDefinition facet2 = new FacetQueryDefinition("External Emails", "NOT from_addr:*@acme.com");
        FacetQueryDefinition facet3 = new FacetQueryDefinition("Internal Emails", "from_addr:*@acme.com");
        
        assertThat(facet1).isEqualTo(facet2);
        assertThat(facet1).isNotEqualTo(facet3);
        assertThat(facet1.hashCode()).isEqualTo(facet2.hashCode());
    }

    @Test
    void facetQueryDefinitionWithComplexSolrQuery() {
        FacetQueryDefinition complexFacet = new FacetQueryDefinition(
                "Recent Urgent Emails", 
                "sent_at:[NOW-7DAY TO *] AND subject:urgent"
        );
        
        assertThat(complexFacet.label()).isEqualTo("Recent Urgent Emails");
        assertThat(complexFacet.query()).isEqualTo("sent_at:[NOW-7DAY TO *] AND subject:urgent");
    }

    @Test
    void facetQueryDefinitionWithDateRangeQuery() {
        FacetQueryDefinition dateRangeFacet = new FacetQueryDefinition(
                "Last Week", 
                "sent_at:[NOW-7DAY TO NOW-1DAY]"
        );
        
        assertThat(dateRangeFacet.label()).isEqualTo("Last Week");
        assertThat(dateRangeFacet.query()).isEqualTo("sent_at:[NOW-7DAY TO NOW-1DAY]");
    }

    @Test
    void facetQueryDefinitionWithBooleanQuery() {
        FacetQueryDefinition booleanFacet = new FacetQueryDefinition(
                "Meeting Related", 
                "(subject:meeting OR body:meeting) AND NOT subject:cancel*"
        );
        
        assertThat(booleanFacet.label()).isEqualTo("Meeting Related");
        assertThat(booleanFacet.query()).isEqualTo("(subject:meeting OR body:meeting) AND NOT subject:cancel*");
    }
}