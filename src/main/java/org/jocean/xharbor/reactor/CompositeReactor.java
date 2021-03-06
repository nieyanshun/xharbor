package org.jocean.xharbor.reactor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

import org.jocean.idiom.Ordered;
import org.jocean.xharbor.api.TradeReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Single;
import rx.functions.Action0;
import rx.functions.Func3;

public class CompositeReactor implements TradeReactor, Ordered {

    @Override
    public String toString() {
        final int maxLen = 10;
        final StringBuilder builder = new StringBuilder();
        builder.append("CompositeReactor [reactors=")
                .append(_reactors != null ? _reactors.subList(0, Math.min(_reactors.size(), maxLen)) : null)
                .append(", ordinal=").append(_ordinal).append("]");
        return builder.toString();
    }

    private static final TradeReactor[] EMPTY_REACTOR = new TradeReactor[0];
    private static final Comparator<TradeReactor> ORDER_REACTOR_DESC = new Comparator<TradeReactor>() {
        @Override
        public int compare(final TradeReactor o1, final TradeReactor o2) {
            if ((o1 instanceof Ordered) && (o2 instanceof Ordered)) {
                return ((Ordered)o2).ordinal() - ((Ordered)o1).ordinal();
            } else if (o1 instanceof Ordered) {
                // o2 is not ordered
                return -1;
            } else if (o2 instanceof Ordered) {
                // o1 is not ordered
                return 1;
            } else {
                // either o1 nor o2 is ordered
                return o1.hashCode() - o2.hashCode();
            }
        }};

    private static final Logger LOG = LoggerFactory.getLogger(CompositeReactor.class);

    public CompositeReactor(final Func3<TradeReactor[],ReactContext,InOut,Single<? extends InOut>> compositeReactor) {
        this._compositeReactor = compositeReactor;
    }

    public void setOrdinal(final int ordinal) {
        this._ordinal = ordinal;
    }

    public Action0 addReactor(final TradeReactor reactor) {
        this._reactors.add(reactor);
        updateStampAndRule();
        return () -> removeReactor(reactor);
    }

    void removeReactor(final TradeReactor reactor) {
        this._reactors.remove(reactor);
        updateStampAndRule();
    }

    private void updateStampAndRule() {
        final int newStamp = this._stampProvider.incrementAndGet();

        while (this._descReactorsRef.getStamp() < newStamp) {
            this._descReactorsRef.attemptStamp(this._descReactorsRef.getReference(), newStamp);
        }

        if (this._descReactorsRef.getStamp() == newStamp) {
            // now this stamp is the newest
            final TradeReactor[] newReactors = this._reactors.toArray(EMPTY_REACTOR);
            Arrays.sort(newReactors, ORDER_REACTOR_DESC);
            if (this._descReactorsRef.compareAndSet(this._descReactorsRef.getReference(), newReactors,
                    newStamp, newStamp)) {
                LOG.info("CompositeReactor's rule has update to stamp({}) success.", newStamp);
            } else {
                LOG.info("CompositeReactor's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                        newStamp, this._descReactorsRef.getStamp());
            }
        } else {
            LOG.info("CompositeReactor's rule try update to stamp({}) failed, bcs other newest stamp({}) exist.",
                    newStamp, this._descReactorsRef.getStamp());
        }
    }

    @Override
    public Single<Boolean> match(final ReactContext ctx, final InOut io) {
        return Single.just(true);
    }

    @Override
    public Single<? extends InOut> react(final ReactContext ctx, final InOut io) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("try {} for trade {}", this, ctx.trade());
        }
        final TradeReactor[] reactors = this._descReactorsRef.getReference();
        if (null == reactors ||
            (null != reactors && reactors.length == 0)) {
            return Single.<InOut>just(null);
        } else {
            return _compositeReactor.call(reactors, ctx, io);
//            return TradeReactor.OP.all(Arrays.asList(reactors), ctx, io);
        }
    }

    @Override
    public int ordinal() {
        return this._ordinal;
    }

    private final AtomicInteger _stampProvider = new AtomicInteger(0);
    private final List<TradeReactor> _reactors = new CopyOnWriteArrayList<>();
    private final AtomicStampedReference<TradeReactor[]> _descReactorsRef = new AtomicStampedReference<>(null, 0);
    private int _ordinal = 0;
    private final Func3<TradeReactor[],ReactContext,InOut,Single<? extends InOut>> _compositeReactor;
}
