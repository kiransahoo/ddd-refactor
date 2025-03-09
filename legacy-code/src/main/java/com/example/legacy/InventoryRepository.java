package com.example.legacy;

/**
 * JPA repository that also includes domain logic => violation.
 */
public class InventoryRepository {

    public SomeDomainAggregate loadAggregate(String id) {
        // pretend to do JPA or DB
        SomeDomainAggregate agg = new SomeDomainAggregate(id, 50);
        if (agg.getStock() > 1000) {
            // domain logic inside repository
            System.out.println("Huge stock!");
        }
        return agg;
    }

    public void saveAggregate(SomeDomainAggregate agg) {
        // domain logic again
        if (agg.getStock() < 0) {
            throw new RuntimeException("Cannot have negative stock");
        }
        // pretend to do an entityManager.persist(agg)
        System.out.println("Saving aggregator => id=" + agg.getId() + ", stock=" + agg.getStock());
    }
}
