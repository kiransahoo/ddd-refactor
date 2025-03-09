package com.example.legacy;

/**
 * A domain aggregator that calls DB code => violation.
 */
public class SomeDomainAggregate {

    private final String id;
    private int stock;

    public SomeDomainAggregate(String id, int stock) {
        this.id = id;
        this.stock = stock;
    }

    public String getId() { return id; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public void directDbCall() {
        // aggregator calling DB => not hex
        System.out.println("Pretending to do entityManager.persist(...) inside aggregator => violation");
    }
}
