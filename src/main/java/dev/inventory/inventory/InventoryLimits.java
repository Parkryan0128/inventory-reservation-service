package dev.inventory.inventory;

public final class InventoryLimits {
  public static final int MAX_QUANTITY = 10_000;
  public static final int MAX_STOCK = 1_000_000;
  public static final long MAX_PRICE_CENTS = 1_000_000_000L;
  public static final int MAX_SKU_LENGTH = 64;
  public static final int MAX_NAME_LENGTH = 160;
  public static final String SKU_PATTERN = "[A-Z0-9_-]{1," + MAX_SKU_LENGTH + "}";
  public static final String CURRENCY_PATTERN = "USD|CAD";

  private InventoryLimits() {}
}
