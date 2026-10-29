package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.Map;

import gregtech.api.interfaces.IOutputTransaction;

/** Shared GT transaction protocol. Protection belongs to GT's helper, not to this stock store. */
public abstract class PartitionOutputTransaction<K, T>
    implements IOutputTransaction<K, T>, IOutputTransaction.IRecipeCheckAware, IOutputTransaction.IProtectOutputAware {

    protected final PartitionStore<K>.Transaction transaction;
    private boolean recipeCheck;
    private boolean protectOutput;
    private boolean active = true;

    protected PartitionOutputTransaction(final PartitionStore<K> store) {
        this.transaction = store.transaction();
    }

    @Override
    public final void setRecipeCheck(final boolean recipeCheck) {
        this.requireActive();
        this.recipeCheck = recipeCheck;
    }

    @Override
    public final void setProtectOutput(final boolean protectOutput) {
        this.requireActive();
        this.protectOutput = protectOutput;
    }

    public final boolean isRecipeCheck() {
        return this.recipeCheck;
    }

    public final boolean isProtectOutput() {
        return this.protectOutput;
    }

    /** Includes failed-commit evidence; never interpret this plan as another materialized inventory. */
    public final Map<K, Long> pending() {
        return this.transaction.pending();
    }

    @Override
    public final boolean hasAvailableSpace() {
        this.requireActive();
        return this.transaction.hasAvailableSpace();
    }

    @Override
    public void complete(final K identity) {
        this.requireActive();
        this.transaction.complete(identity);
    }

    @Override
    public final void commit() {
        this.requireActive();
        this.active = false;
        if (this.recipeCheck) throw new IllegalStateException("Recipe simulation must never commit stock");
        this.transaction.commit();
    }

    protected final void requireActive() {
        if (!this.active) throw new IllegalStateException("GT partition transaction is spent");
    }
}
