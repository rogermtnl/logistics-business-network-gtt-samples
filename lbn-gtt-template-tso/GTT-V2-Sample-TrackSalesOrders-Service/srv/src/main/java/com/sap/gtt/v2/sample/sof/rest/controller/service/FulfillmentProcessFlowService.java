package com.sap.gtt.v2.sample.sof.rest.controller.service;

import com.sap.gtt.v2.sample.sof.constant.Constants;
import com.sap.gtt.v2.sample.sof.constant.FulfillmentProcessMilestoneEnum;
import com.sap.gtt.v2.sample.sof.domain.ProcessEventDirectory;
import com.sap.gtt.v2.sample.sof.domain.TrackedProcess;
import com.sap.gtt.v2.sample.sof.odata.filter.FilterCondition;
import com.sap.gtt.v2.sample.sof.odata.filter.FilterExpressionBuilder;
import com.sap.gtt.v2.sample.sof.odata.model.DeliveryItem;
import com.sap.gtt.v2.sample.sof.odata.model.SalesOrderItem;
import com.sap.gtt.v2.sample.sof.rest.controller.domain.fulfillmentprocessflow.FulfillmentProcessFlow;
import com.sap.gtt.v2.sample.sof.rest.controller.domain.fulfillmentprocessflow.Lane;
import com.sap.gtt.v2.sample.sof.service.client.GTTCoreServiceClient;
import org.apache.olingo.odata2.api.uri.expression.BinaryOperator;
import org.apache.olingo.odata2.api.uri.expression.FilterExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.sap.gtt.v2.sample.sof.constant.Constants.PROCESS_EVENT_DIRECTORY_ENTITY_NAME;
import static com.sap.gtt.v2.sample.sof.constant.Constants.URL_SPLITTER;
import static com.sap.gtt.v2.sample.sof.service.client.GTTCoreServiceClient.EXPAND;
import static com.sap.gtt.v2.sample.sof.service.client.GTTCoreServiceClient.FILTER;
import static java.lang.String.format;

@Service
public class FulfillmentProcessFlowService {

    private static final String REJECTION_STATUS_COMPLETE_REJECT_CODE = "C";
    private static final String PROCESS = "process";

    @Autowired
    private GTTCoreServiceClient gttCoreServiceClient;

    public FulfillmentProcessFlow generateFulfillmentProcessFlow(UUID salesOrderItemId) {
        SalesOrderItem salesOrderItem = querySalesOrderItem(salesOrderItemId);

        List<Lane> lanes = initLanes(salesOrderItem);
        updateLanes(lanes, salesOrderItem);

        FulfillmentProcessFlow flow = new FulfillmentProcessFlow();
        flow.setLanes(lanes);

        return flow;
    }

    private SalesOrderItem querySalesOrderItem(UUID salesOrderItemId) {
        String query = UriComponentsBuilder.fromUriString(format("/SalesOrderItem(guid'%s')", salesOrderItemId))
                .queryParam(EXPAND, "scheduleLines,deliveryItems")
                .build().encode().toUriString();
        return gttCoreServiceClient.readEntity(query, SalesOrderItem.class);
    }

    private List<Lane> initLanes(SalesOrderItem salesOrderItem) {
        List<Lane> lanes = new ArrayList<>();
        String rejectionStatusCode = salesOrderItem.getRejectionStatusCode();
        List<DeliveryItem> deliveryItems = salesOrderItem.getDeliveryItems();

        if (REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode) && deliveryItems.isEmpty()) {
            EnumSet.allOf(FulfillmentProcessMilestoneEnum.class).stream()
                    .filter(m -> m.getName().equals(FulfillmentProcessMilestoneEnum.SALES_ORDER_ITEM_CREATED.getName())
                            || m.getName().equals(FulfillmentProcessMilestoneEnum.SALES_ORDER_ITEM_REJECTED.getName()))
                    .forEach(m -> {
                        Lane lane = new Lane();
                        lane.setId(String.valueOf(m.getPosition()));
                        lane.setName(m.getName());
                        lane.setPosition(m.getPosition());
                        lanes.add(lane);
                    });
        } else {
            EnumSet.allOf(FulfillmentProcessMilestoneEnum.class).stream()
                    .filter(m -> !m.getName().equals(FulfillmentProcessMilestoneEnum.SALES_ORDER_ITEM_REJECTED.getName()))
                    .forEach(milestone -> {
                        Lane lane = new Lane();
                        lane.setId(String.valueOf(milestone.getPosition()));
                        lane.setName(milestone.getName());
                        lane.setPosition(milestone.getPosition());
                        lanes.add(lane);
                    });
        }

        return lanes;
    }

    private void updateLanes(List<Lane> lanes, SalesOrderItem salesOrderItem) {
        lanes.forEach(lane -> updateLane(lane, salesOrderItem));
    }

    private void updateLane(Lane lane, SalesOrderItem salesOrderItem) {
        String name = lane.getName();
        FulfillmentProcessMilestoneEnum milestone = EnumSet.allOf(FulfillmentProcessMilestoneEnum.class).stream()
                .filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException(String.format("Unsupported milestone %s.", name)));
        switch (milestone) {
            case SALES_ORDER_ITEM_CREATED:
                updateMilestoneOfSalesOrderItemCreated(lane, salesOrderItem);
                break;
            case SALES_ORDER_ITEM_CONFIRMED:
                updateMilestoneOfSalesOrderItemConfirmed(lane, salesOrderItem);
                break;
            case DELIVERY_CREATED:
                updateMilestoneOfDeliveryCreated(lane, salesOrderItem);
                break;
            case DELIVERY_GOODS_ISSUED:
                updateMilestoneOfDeliveryGoodsIssued(lane, salesOrderItem);
                break;
            case DELIVERY_COMPLETED:
                updateMilestoneOfDeliveryCompleted(lane, salesOrderItem);
                break;
            case SALES_ORDER_ITEM_REJECTED:
                updateMilestoneOfSalesOrderItemRejected(lane, salesOrderItem);
                break;
            default:
        }
    }

    private void updateMilestoneOfSalesOrderItemCreated(Lane lane, SalesOrderItem salesOrderItem) {
        BigDecimal orderQuantity = Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);
        lane.setCount(orderQuantity);
        lane.setTotal(orderQuantity);
    }

    private void updateMilestoneOfSalesOrderItemConfirmed(Lane lane, SalesOrderItem salesOrderItem) {
        String rejectionStatusCode = salesOrderItem.getRejectionStatusCode();
        BigDecimal saleOrderItemOrderQuantity = Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);
        List<DeliveryItem> deliveryItems = salesOrderItem.getDeliveryItems();

        BigDecimal count = REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode) && !deliveryItems.isEmpty() ?
                deliveryItems.stream()
                        .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add) :
                salesOrderItem.getScheduleLines().stream()
                        .map(scheduleLine -> Optional.ofNullable(scheduleLine.getConfirmedQuantity()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode)) {
            lane.setRejectCount(saleOrderItemOrderQuantity.subtract(count));
        }
        lane.setCount(count);
        lane.setTotal(saleOrderItemOrderQuantity);
    }

    private void updateMilestoneOfDeliveryCreated(Lane lane, SalesOrderItem salesOrderItem) {
        String rejectionStatusCode = salesOrderItem.getRejectionStatusCode();
        List<DeliveryItem> deliveryItems = salesOrderItem.getDeliveryItems();

        BigDecimal count = deliveryItems.stream()
                .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderQuantity = REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode) && !deliveryItems.isEmpty() ?
                count : Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);

        lane.setCount(count);
        lane.setTotal(orderQuantity);

    }

    private void updateMilestoneOfDeliveryGoodsIssued(Lane lane, SalesOrderItem salesOrderItem) {
        String rejectionStatusCode = salesOrderItem.getRejectionStatusCode();
        List<DeliveryItem> deliveryItems = salesOrderItem.getDeliveryItems();

        BigDecimal orderQuantity = REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode) && !deliveryItems.isEmpty() ?
                deliveryItems.stream()
                        .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add) :
                Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);

        if (deliveryItems.isEmpty()) {
            lane.setCount(BigDecimal.ZERO);
        } else {
            Set<String> deliveryItemTrackingIds = deliveryItems.stream().map(DeliveryItem::getTrackingId).collect(Collectors.toSet());
            List<FilterExpression> expressions = new ArrayList<>();

            deliveryItemTrackingIds.forEach(trackingId -> {
                List<FilterCondition> conditions = new ArrayList<>();
                conditions.add(new FilterCondition("process/trackingId", FilterCondition.EDM_TYPE_STRING, trackingId, BinaryOperator.EQ));
                conditions.add(new FilterCondition("event/eventType", FilterCondition.EDM_TYPE_STRING, Constants.GTT_MODEL_NAMESPACE + ".Delivery.GoodsIssued", BinaryOperator.EQ));
                expressions.add(FilterExpressionBuilder.createFilterExpression(conditions, BinaryOperator.AND));
            });
            FilterExpression filter = expressions.get(0);
            for (int index = 1; index < expressions.size(); index++) {
                filter = FilterExpressionBuilder.createFilterExpression(filter, expressions.get(index), BinaryOperator.OR);
            }

            String query = UriComponentsBuilder.fromUriString(URL_SPLITTER + PROCESS_EVENT_DIRECTORY_ENTITY_NAME)
                    .queryParam(EXPAND, PROCESS)
                    .queryParam(FILTER, filter.getExpressionString())
                    .build().encode().toUriString();
            List<ProcessEventDirectory> processEventDirectories = gttCoreServiceClient.readEntitySetAll(query, ProcessEventDirectory.class).getResults();

            Set<String> reportedTrackingIds = processEventDirectories.stream()
                    .map(ProcessEventDirectory::getProcess)
                    .map(TrackedProcess::getTrackingId)
                    .collect(Collectors.toSet());
            BigDecimal count = deliveryItems.stream()
                    .filter(deliveryItem -> reportedTrackingIds.contains(deliveryItem.getTrackingId()))
                    .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            lane.setCount(count);
        }
        lane.setTotal(orderQuantity);
    }

    private void updateMilestoneOfDeliveryCompleted(Lane lane, SalesOrderItem salesOrderItem) {
        String rejectionStatusCode = salesOrderItem.getRejectionStatusCode();

        List<DeliveryItem> deliveryItems = salesOrderItem.getDeliveryItems();
        List<DeliveryItem> completedDeliveryItems = deliveryItems.stream()
                .filter(deliveryItem -> Constants.EXECUTION_STATUS_COMPLETED.equals(deliveryItem.getExecutionStatusCode()))
                .collect(Collectors.toList());

        BigDecimal count = completedDeliveryItems.stream()
                .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal orderQuantity = REJECTION_STATUS_COMPLETE_REJECT_CODE.equals(rejectionStatusCode) && !deliveryItems.isEmpty() ?
                deliveryItems.stream()
                        .map(deliveryItem -> Optional.ofNullable(deliveryItem.getOrderQuantity()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add) :
                Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);

        lane.setCount(count);
        lane.setTotal(orderQuantity);
    }

    private void updateMilestoneOfSalesOrderItemRejected(Lane lane, SalesOrderItem salesOrderItem) {
        BigDecimal saleOrderItemOrderQuantity = Optional.ofNullable(salesOrderItem.getOrderQuantity()).orElse(BigDecimal.ZERO);
        lane.setCount(saleOrderItemOrderQuantity);
        lane.setTotal(saleOrderItemOrderQuantity);
    }

}
