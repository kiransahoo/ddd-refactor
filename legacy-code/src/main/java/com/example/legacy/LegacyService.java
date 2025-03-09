package com.example.legacy;

/**
 * A service with questionable domain logic. 
 */
public class LegacyService {

    private final InventoryRepository inventoryRepository;

    public LegacyService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public void processItem(String itemId, int quantity) {
        // domain logic in the service => not pure application logic
        if (quantity > 100) {
            System.out.println("Big quantity. Possibly domain rule not in aggregator");
        }

        // aggregator with direct DB calls => also questionable
        SomeDomainAggregate agg = inventoryRepository.loadAggregate(itemId);
        agg.setStock(agg.getStock() + quantity);

        inventoryRepository.saveAggregate(agg);
    }
}
