package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.TradeJpaEntity;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeeSnapshot;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.BidId;
import com.example.market.domain.trading.ListingId;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class TradeJpaMapper {

    private TradeJpaMapper() {}

    public static TradeJpaEntity toEntity(Trade t) {
        TradeJpaEntity e = new TradeJpaEntity();
        e.setId(t.id().value());
        e.setSkuId(t.skuId().value());
        e.setListingId(t.listingId().value());
        e.setBidId(t.bidId().value());
        e.setSellerId(t.sellerId().value());
        e.setBuyerId(t.buyerId().value());
        e.setPrice(t.price().amount());
        e.setCurrencyCode(t.price().currency().getCurrencyCode());

        FeeSnapshot s = t.feeSnapshot();
        e.setFeeSellerRate(s.sellerCommissionRate());
        e.setFeeBuyerRate(s.buyerCommissionRate());
        e.setFeeInspection(s.inspectionFee().amount());
        e.setFeeShipping(s.shippingFee().amount());
        e.setFeeProcessing(s.fixedProcessingFee().amount());
        e.setFeeSellerCommission(s.sellerCommission().amount());
        e.setFeeBuyerCommission(s.buyerCommission().amount());
        e.setBuyerCharge(s.buyerCharge().amount());
        e.setSellerNet(s.sellerNet().amount());

        e.setStatus(t.status());
        e.setPgPaymentId(t.pgPaymentId());
        e.setInspectionFailReason(t.inspectionFailReason());
        e.setCreatedAt(t.createdAt());
        e.setUpdatedAt(t.updatedAt());
        e.setVersion(t.version());
        return e;
    }

    public static Trade toDomain(TradeJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrencyCode());
        Money price = Money.of(e.getPrice(), currency);
        FeeSnapshot snap = new FeeSnapshot(
                price,
                e.getFeeSellerRate(),
                e.getFeeBuyerRate(),
                Money.of(e.getFeeInspection(), currency),
                Money.of(e.getFeeShipping(), currency),
                Money.of(e.getFeeProcessing(), currency),
                Money.of(e.getFeeSellerCommission(), currency),
                Money.of(e.getFeeBuyerCommission(), currency),
                Money.of(e.getBuyerCharge(), currency),
                Money.of(e.getSellerNet(), currency)
        );
        return Trade.restore(
                new TradeId(e.getId()), new SkuId(e.getSkuId()),
                new ListingId(e.getListingId()), new BidId(e.getBidId()),
                UserId.of(e.getSellerId()), UserId.of(e.getBuyerId()),
                price, snap, e.getStatus(),
                e.getPgPaymentId(), e.getInspectionFailReason(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
