package com.kyou.metransferdrive.transfer;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** One prepaid transfer ledger, never a reusable energy battery. Loaded/uncertain evidence cannot authorize refunds. */
final class TransferEnergyAccount implements TransferTransaction.Energy {

    interface Port {

        boolean valid();

        boolean infinite();

        double stored();

        double capacity();

        double idle();

        double extract(double physical, boolean simulate);

        /** IEnergyGrid returns already-owned OVERFLOW, not an unaccepted remainder. */
        double refund(double physical);
    }

    private final Runnable checkpoint;
    private final TransferPowerBudget budget;
    private Object grid;
    private Port port;
    private int unit;
    private double multiplier;
    private String stage = "IDLE";
    private long requested;
    private long authorized;
    private long delivered;
    private double debitRequest;
    private double prepaid;
    private double charged;
    private double refund;
    private double overflow;
    private boolean refundAttempted;
    private boolean refundAcknowledged;
    private boolean deferred;
    private String failure = "";
    private NBTBase loadedEvidence;

    TransferEnergyAccount(final Runnable checkpoint, final TransferPowerBudget budget) {
        this.checkpoint = checkpoint;
        this.budget = budget;
    }

    TransferEnergyAccount bind(final Object grid, final Port port, final int unit, final double multiplier) {
        if (this.isPending() || grid == null
            || port == null
            || unit <= 0
            || !Double.isFinite(multiplier)
            || multiplier <= 0) {
            throw new IllegalStateException("Cannot bind transfer energy account");
        }
        this.grid = grid;
        this.port = port;
        this.unit = unit;
        this.multiplier = multiplier;
        this.deferred = false;
        this.requested = this.authorized = this.delivered = 0;
        this.debitRequest = this.prepaid = this.charged = this.refund = this.overflow = 0;
        this.refundAttempted = this.refundAcknowledged = false;
        this.failure = "";
        return this;
    }

    @Override
    public long reserve(final long amount) {
        if (this.isPending() || amount < 0 || this.port == null) {
            throw new IllegalStateException("Invalid transfer energy prepayment");
        }
        if (amount == 0) {
            this.detach();
            return 0;
        }
        this.requested = Math.min(amount, TransferEnergy.MAX_AMOUNT);
        final double allowance = this.budget.allowance(this.grid, this.port, this.multiplier);
        final long affordable = TransferEnergy.authorized(allowance, this.unit, this.multiplier, this.requested);
        if (affordable == 0 || !this.port.valid()) {
            this.deferred = true;
            this.detach();
            return 0;
        }
        this.debitRequest = TransferEnergy.charge(affordable, this.unit, this.multiplier);
        this.budget.reserve(this.grid, this.debitRequest);
        this.stage = "DEBITING";
        try {
            this.checkpoint.run();
            this.prepaid = this.port.extract(this.debitRequest, false);
            TransferPowerBudget.checked(this.prepaid, this.debitRequest);
            this.authorized = TransferEnergy.authorized(this.prepaid, this.unit, this.multiplier, affordable);
            this.stage = "RESERVED";
            this.checkpoint.run();
            if (this.authorized == 0) {
                this.deferred = true;
                this.settle(0, true);
            }
            return this.authorized;
        } catch (final RuntimeException problem) {
            if (!"UNCERTAIN".equals(this.stage) && !"REFUND_BLOCKED".equals(this.stage)) {
                this.isolate(problem);
            }
            throw problem;
        }
    }

    @Override
    public void settle(final long delivered, final boolean certain) {
        if (!this.isPending()) {
            return;
        }
        if (!certain) {
            if (delivered >= 0 && delivered <= this.authorized) {
                this.delivered = delivered;
            }
            if (!"UNCERTAIN".equals(this.stage) && !"REFUND_BLOCKED".equals(this.stage)) {
                this.isolate(new IllegalStateException("Inventory result is uncertain; prepayment is not refundable"));
            }
            return;
        }
        if (!"RESERVED".equals(this.stage) || delivered < 0 || delivered > this.authorized) {
            throw new IllegalStateException("Invalid or repeated energy settlement");
        }
        this.delivered = delivered;
        this.charged = TransferEnergy.charge(delivered, this.unit, this.multiplier);
        this.refund = TransferEnergy.difference(this.prepaid, this.charged);
        try {
            if (this.refund > 0) {
                if (!this.port.valid()) {
                    this.stage = "REFUND_BLOCKED";
                    this.failure = "Original source Grid unavailable; refund was not attempted";
                    this.detach();
                    this.checkpoint.run();
                    throw new IllegalStateException(this.failure);
                }
                this.stage = "REFUNDING";
                this.refundAttempted = true;
                this.checkpoint.run();
                this.overflow = this.port.refund(this.refund);
                if (!Double.isFinite(this.overflow) || this.overflow < 0) {
                    throw new IllegalStateException("Invalid refund acknowledgement");
                }
                this.refundAcknowledged = true;
            }
            this.stage = "IDLE";
            this.checkpoint.run();
            this.detach();
        } catch (final RuntimeException problem) {
            if (!"REFUND_BLOCKED".equals(this.stage)) {
                this.isolate(problem);
            }
            throw problem;
        }
    }

    private void isolate(final RuntimeException problem) {
        this.stage = "UNCERTAIN";
        final String text = problem.getClass()
            .getName() + ": "
            + String.valueOf(problem.getMessage());
        this.failure = text.substring(0, Math.min(256, text.length()));
        this.detach();
        this.checkpoint.run();
    }

    void detach() {
        this.grid = null;
        this.port = null;
    }

    void skip(final boolean defer) {
        if (this.isPending()) {
            throw new IllegalStateException("Cannot discard an unsettled energy account");
        }
        this.deferred = defer;
        this.detach();
    }

    boolean isPending() {
        return this.loadedEvidence != null || !"IDLE".equals(this.stage);
    }

    @Override
    public boolean isDeferred() {
        return this.deferred;
    }

    void write(final NBTTagCompound data) {
        data.removeTag("transferEnergy");
        if (this.loadedEvidence != null) {
            data.setTag("transferEnergy", this.loadedEvidence.copy());
        } else if (this.isPending()) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("version", 1);
            tag.setString("stage", this.stage);
            tag.setInteger("unit", this.unit);
            tag.setDouble("multiplier", this.multiplier);
            tag.setLong("requested", this.requested);
            tag.setLong("authorized", this.authorized);
            tag.setLong("confirmedDelivered", this.delivered);
            tag.setDouble("debitRequest", this.debitRequest);
            tag.setDouble("prepaid", this.prepaid);
            tag.setDouble("charged", this.charged);
            tag.setDouble("refund", this.refund);
            tag.setDouble("overflow", this.overflow);
            tag.setBoolean("refundAttempted", this.refundAttempted);
            tag.setBoolean("refundAcknowledged", this.refundAcknowledged);
            tag.setString("failure", this.failure);
            data.setTag("transferEnergy", tag);
        }
    }

    void read(final NBTTagCompound data) {
        this.detach();
        this.loadedEvidence = data.hasKey("transferEnergy") ? data.getTag("transferEnergy")
            .copy() : null;
        this.stage = this.loadedEvidence == null ? "IDLE" : "UNCERTAIN";
    }
}
