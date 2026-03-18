package com.theyawns.ecommerce.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that JSON payloads used in demo/load scripts (k6, demo-scenarios.sh,
 * load-test.sh, load-sample-data.sh) deserialize correctly into DTOs and pass
 * Bean Validation. This catches field-name mismatches (e.g., "items" vs "lineItems")
 * and missing required fields before they reach production scripts.
 */
@DisplayName("ScriptPayloadValidation - Demo/load script JSON payloads match DTO contracts")
class ScriptPayloadValidationTest {

    private static ObjectMapper objectMapper;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        objectMapper = new ObjectMapper();
        try (ValidatorFactory factory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("Valid order payloads")
    class ValidOrderPayloads {

        @Test
        @DisplayName("should accept k6 demo-ambient single line item payload")
        void shouldAcceptK6DemoAmbientPayload() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": [
                            {"productId": "test-product-456", "quantity": 1, "unitPrice": 29.99}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isEmpty();
            assertThat(order.getCustomerId()).isEqualTo("test-customer-123");
            assertThat(order.getLineItems()).hasSize(1);
            assertThat(order.getLineItems().get(0).getProductId()).isEqualTo("test-product-456");
            assertThat(order.getLineItems().get(0).getQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("should accept demo-scenarios multi line item payload")
        void shouldAcceptDemoScenariosMultiLineItemPayload() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": [
                            {"productId": "test-product-1", "quantity": 1, "unitPrice": 299.99},
                            {"productId": "test-product-2", "quantity": 2, "unitPrice": 49.99}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isEmpty();
            assertThat(order.getLineItems()).hasSize(2);
            assertThat(order.getLineItems().get(0).getProductId()).isEqualTo("test-product-1");
            assertThat(order.getLineItems().get(1).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("should accept k6 fail-order high quantity payload")
        void shouldAcceptK6FailOrderHighQuantityPayload() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": [
                            {"productId": "test-product-456", "quantity": 99000, "unitPrice": 9.99}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isEmpty();
            assertThat(order.getLineItems().get(0).getQuantity()).isEqualTo(99000);
        }
    }

    @Nested
    @DisplayName("Valid customer payloads")
    class ValidCustomerPayloads {

        @Test
        @DisplayName("should accept load-sample-data customer creation payload")
        void shouldAcceptLoadSampleDataCustomerPayload() throws Exception {
            // Arrange
            final String json = """
                    {
                        "email": "test@example.com",
                        "name": "Test Customer",
                        "address": "123 Test St"
                    }
                    """;

            // Act
            final CustomerDTO customer = objectMapper.readValue(json, CustomerDTO.class);
            final Set<ConstraintViolation<CustomerDTO>> violations = validator.validate(customer);

            // Assert
            assertThat(violations).isEmpty();
            assertThat(customer.getEmail()).isEqualTo("test@example.com");
            assertThat(customer.getName()).isEqualTo("Test Customer");
            assertThat(customer.getAddress()).isEqualTo("123 Test St");
        }
    }

    @Nested
    @DisplayName("Valid product payloads")
    class ValidProductPayloads {

        @Test
        @DisplayName("should accept load-sample-data product creation payload")
        void shouldAcceptLoadSampleDataProductPayload() throws Exception {
            // Arrange
            final String json = """
                    {
                        "sku": "TEST-001",
                        "name": "Test Product",
                        "description": "A test product",
                        "price": 29.99,
                        "quantityOnHand": 100,
                        "category": "Electronics"
                    }
                    """;

            // Act
            final ProductDTO product = objectMapper.readValue(json, ProductDTO.class);
            final Set<ConstraintViolation<ProductDTO>> violations = validator.validate(product);

            // Assert
            assertThat(violations).isEmpty();
            assertThat(product.getSku()).isEqualTo("TEST-001");
            assertThat(product.getName()).isEqualTo("Test Product");
            assertThat(product.getQuantityOnHand()).isEqualTo(100);
            assertThat(product.getCategory()).isEqualTo("Electronics");
        }
    }

    @Nested
    @DisplayName("Invalid payloads - validation catches problems")
    class InvalidPayloads {

        @Test
        @DisplayName("should reject order with empty lineItems array")
        void shouldRejectOrderWithEmptyLineItems() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": []
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v ->
                    v.getPropertyPath().toString().equals("lineItems"));
        }

        @Test
        @DisplayName("should reject order with missing customerId")
        void shouldRejectOrderWithMissingCustomerId() throws Exception {
            // Arrange
            final String json = """
                    {
                        "lineItems": [
                            {"productId": "test-product-456", "quantity": 1, "unitPrice": 29.99}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v ->
                    v.getPropertyPath().toString().equals("customerId"));
        }

        @Test
        @DisplayName("should reject order line item with zero quantity")
        void shouldRejectLineItemWithZeroQuantity() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": [
                            {"productId": "test-product-456", "quantity": 0, "unitPrice": 29.99}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v ->
                    v.getPropertyPath().toString().contains("quantity"));
        }

        @Test
        @DisplayName("should reject order line item with zero unitPrice")
        void shouldRejectLineItemWithZeroUnitPrice() throws Exception {
            // Arrange
            final String json = """
                    {
                        "customerId": "test-customer-123",
                        "lineItems": [
                            {"productId": "test-product-456", "quantity": 1, "unitPrice": 0}
                        ]
                    }
                    """;

            // Act
            final OrderDTO order = objectMapper.readValue(json, OrderDTO.class);
            final Set<ConstraintViolation<OrderDTO>> violations = validator.validate(order);

            // Assert
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v ->
                    v.getPropertyPath().toString().contains("unitPrice"));
        }
    }
}
