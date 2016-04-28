/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.cloudstats;

import hudson.Util;
import hudson.model.ModelObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Record of provisioning attempt lifecycle.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class) // Until we gain more confidence in this API
public final class ProvisioningActivity implements ModelObject {

    /**
     * Progress of an activity.
     */
    public enum Phase {
        /**
         * Acquiring the slave is in progress.
         */
        PROVISIONING,
        /**
         * Slave agent is being launched,
         */
        LAUNCHING,
        /**
         * The node is connected as Jenkins slave, possibly running builds.
         */
        OPERATING,
        /**
         * The resources (if any) as well as the computer should be gone.
         *
         * This phase is never started.
         */
        COMPLETED
    }

    public enum Status {
        /**
         * All went well.
         */
        OK,
        /**
         * There was a problem worth looking at, though provisioning managed to proceed.
         *
         * The identification of the cause should be in attachment.
         */
        WARN,
        /**
         * The activity was aborted because of a problem so it never delivered functional slave.
         *
         * The identification of the cause should be in attachment.
         */
        FAIL
    }

    /**
     * Phase execution record.
     *
     * While the phases starts in declared order, they might not complete in that order. Much less previous phase will
     * be completed before next one starts.
     *
     * There are several reasons for that: provisioning listener is called when the results are picked up, the slave
     * might have started launching agent in the meantime. There are plugin that in fact enforce the launch to complete,
     * before completing the {@link hudson.slaves.NodeProvisioner.PlannedNode#future}. To avoid any problems this can cause,
     * the execution of phases is expected to occur in order, the execution will accept attachments regardless if the
     * next phase started or not. For the time tracking purposes, the phase is considered completed as soon as the next
     * phase completes. IOW, despite the fact the slave already started launching, plugin can still append provisioning log.
     */
    public static final class PhaseExecution implements ModelObject {
        private final @Nonnull List<PhaseExecutionAttachment> attachments = new CopyOnWriteArrayList<>();
        private final long started;
        private final @Nonnull Phase phase;

        /*package*/ PhaseExecution(@Nonnull Phase phase) {
            this.started = System.currentTimeMillis();
            this.phase = phase;
        }

        public @Nonnull List<PhaseExecutionAttachment> getAttachments() {
            return Collections.unmodifiableList(attachments);
        }

        public @CheckForNull <T extends PhaseExecutionAttachment> List<T> getAttachments(@Nonnull Class<T> type) {
            List<T> out = new ArrayList<>();
            for (PhaseExecutionAttachment attachment : getAttachments()) {
                if (type.isInstance(attachment)) {
                    out.add(type.cast(attachment));
                }
            }
            return out;
        }

        public @Nonnull Status getStatus() {
            Status status = Status.OK;
            for (PhaseExecutionAttachment a : getAttachments()) {
                if (a.getStatus().ordinal() > status.ordinal()) {
                    status = a.getStatus();
                }
            }
            return status;
        }

        public @Nonnull Date getStarted() {
            return new Date(started);
        }

        public @Nonnull Phase getPhase() {
            return phase;
        }

        @Override
        public @Nonnull String getDisplayName() {
            return phase.toString();
        }

        private void attach(@Nonnull PhaseExecutionAttachment phaseExecutionAttachment) {
            attachments.add(phaseExecutionAttachment);
        }

        @Restricted(DoNotUse.class)
        public @CheckForNull String getUrlName(@Nonnull PhaseExecutionAttachment attachment) {
            String urlName = attachment.getUrlName();
            if (urlName == null) return null;

            if (!attachments.contains(attachment)) throw new IllegalArgumentException(
                    "Attachment not present in current execution"
            );

            int cntr = 0;
            for (PhaseExecutionAttachment a: attachments) {
                if (a.equals(attachment)) break;

                if (urlName.equals(a.getUrlName())) {
                    cntr++;
                }
            }

            if (cntr > 0) {
                return "attachment/" + urlName + ':' + cntr;
            } else {
                return "attachment/" + urlName;
            }
        }

        @Restricted(DoNotUse.class)
        public PhaseExecutionAttachment getAttachment(String urlName) {
            int n = 0;
            int i = urlName.lastIndexOf(':');
            if (i != -1) {
                try {
                    n = Integer.parseInt(urlName.substring(i + 1));
                    urlName = urlName.substring(0, i - 1);
                } catch (NumberFormatException nan) {
                    // It is not expected that ':' is found in the name, though proceed to fail later as the name will not be found
                }
            }

            int cntr = 0;
            for (PhaseExecutionAttachment a: attachments) {
                if (!urlName.equals(a.getUrlName())) continue;

                if (cntr == n) return a;

                cntr++;
            }

            return null;
        }
    }

    /**
     * Activity identifier.
     *
     * Used to a) uniquely identify the activity throughout the lifecycle and b) map computer to its cloud/template.
     */
    public static final class Id {
        private final @Nonnull String cloudName;


        private final @CheckForNull String templateName;

        private final @CheckForNull String nodeName;

        /**
         * Unique identifier of the activity.
         */
        private final int fingerprint;

        /**
         * @param cloudName Name of the cloud that initiated this activity.
         * @param fingerprint Unique id of this activity.
         * @param templateName Name of the template that initiated this activity.
         * @param nodeName Name of the slave to be provisioned. Of the name of the slave is not known ahead, it can
         *                    be <tt>null</tt> cloud stats plugin will update it once it will be known.
         */
        public Id(@Nonnull String cloudName, int fingerprint, @CheckForNull String templateName, @CheckForNull String nodeName) {
            this.cloudName = cloudName;
            this.templateName = templateName;
            this.nodeName = nodeName;
            this.fingerprint = fingerprint;
        }

        public Id(@Nonnull String cloudName, int fingerprint, @CheckForNull String templateName) {
            this.cloudName = cloudName;
            this.templateName = templateName;
            this.nodeName = null;
            this.fingerprint = fingerprint;
        }

        public Id(@Nonnull String cloudName, int fingerprint) {
            this(cloudName, fingerprint, null);
        }

        /*package*/ Id(String cloudName, String templateName, TrackedPlannedNode trackedPlannedNode) {
            this(cloudName, getFingerprint(trackedPlannedNode), templateName);
        }

        /**
         * Name of the cloud that initiated this activity.
         */
        public @Nonnull String getCloudName() {
            return cloudName;
        }

        /**
         * Name of the template used to provision this slave. <tt>null</tt> if no further distinction is needed except for cloud name.
         */
        public @CheckForNull String getTemplateName() {
            return templateName;
        }

        /**
         * Name of the slave to be provisioned by this activity. <tt>null</tt> if not known ahead.
         */
        public @CheckForNull String getNodeName() {
            return nodeName;
        }

        /**
         * Unique fingerprint of this activity.
         */
        public int getFingerprint() {
            return fingerprint;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Id id = (Id) o;
            return fingerprint == id.fingerprint;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fingerprint);
        }

        @Override
        public String toString() {
            return String.format("ProvisioningActivity for %s/%s/%s (%d)", cloudName, templateName, nodeName, fingerprint);
        }

        private static int getFingerprint(TrackedPlannedNode node) {
            return System.identityHashCode(node);
        }
    }

    private final @Nonnull Id id;

    @GuardedBy("id")
    private @Nonnull String name;

    /**
     * All phases the activity has started so far.
     */
    private final Map<Phase, PhaseExecution> progress;
    {
        progress = new LinkedHashMap<>(Phase.values().length);
        progress.put(Phase.PROVISIONING, null);
        progress.put(Phase.LAUNCHING, null);
        progress.put(Phase.OPERATING, null);
        progress.put(Phase.COMPLETED, null);
    }

    public ProvisioningActivity(@Nonnull Id id) {
        this.id = id;
        enter(Phase.PROVISIONING);

        // No need to synchronize since in constructor
        String name = id.nodeName;
        if (name == null) {
            name = id.templateName;
        }
        if (name == null) {
            name = id.cloudName;
        }
        this.name = name;
    }

    public @Nonnull Id getId() {
        return id;
    }

    public @Nonnull Date getStarted() {
        synchronized (progress) {
            return progress.get(Phase.PROVISIONING).getStarted();
        }
    }

    /**
     * {@link PhaseExecution} or null in case it is/was not executed.
     */
    public @CheckForNull PhaseExecution getPhaseExecution(@Nonnull Phase phase) {
        synchronized (progress) {
            return progress.get(phase);
        }
    }

    /**
     * Get sorted mapping of all phase executions.
     *
     * @return Map of {@link Phase} and nullable {@link PhaseExecution}.
     */
    public @Nonnull Map<Phase, PhaseExecution> getPhaseExecutions() {
        Map<Phase, PhaseExecution> ret = new LinkedHashMap<>(4);
        synchronized (progress) {
            ret.put(Phase.PROVISIONING, progress.get(Phase.PROVISIONING));
            ret.put(Phase.LAUNCHING, progress.get(Phase.LAUNCHING));
            ret.put(Phase.OPERATING, progress.get(Phase.OPERATING));
            ret.put(Phase.COMPLETED, progress.get(Phase.COMPLETED));
        }
        return ret;
    }


    /**
     * Status of the activity as a whole.
     *
     * It is the works status of any of the phases, OK by default.
     */
    public @Nonnull Status getStatus() {
        synchronized (progress) {
            Status status = Status.OK;
            for (PhaseExecution e : progress.values()) {
                if (e == null) continue;
                Status s = e.getStatus();
                if (status.ordinal() < s.ordinal()) {
                    status = s;
                }
            }
            return status;
        }
    }

    /**
     * Make the phase of this activity entered.
     *
     * @throws IllegalArgumentException In case phases are entered repeatedly.
     */
    public void enter(@Nonnull Phase phase) {
        synchronized (progress) {
            if (progress.get(phase) != null) throw new IllegalStateException("The phase " + phase + " has already started");

            progress.put(phase, new PhaseExecution(phase));
        }
    }

    /**
     * Make sure the phase of this activity is entered.
     *
     * Exposed for convenience of clients that can be invoked repeatedly and have no easier way to tell if phase was
     * entered already, such as launch listener.
     */
    public void enterIfNotAlready(@Nonnull Phase phase) {
        synchronized (progress) {
            if (progress.get(phase) != null) return;
            progress.put(phase, new PhaseExecution(phase));
        }
    }

    public void attach(Phase phase, PhaseExecutionAttachment attachment) {
        PhaseExecution execution = getPhaseExecution(phase);
        if (execution == null) throw new IllegalArgumentException("Phase " + phase + " not entered yet");
        execution.attach(attachment);

        // Complete the activity upon first failure
        if (attachment.getStatus() == Status.FAIL) {
            enterIfNotAlready(Phase.COMPLETED);
        }
    }

    public @Nonnull String getName() {
        synchronized (id) {
            return name;
        }
    }

    @Override @Restricted(DoNotUse.class) // Stapler only
    public @Nonnull String getDisplayName() {
        return "Activity " + getName();
    }

    /**
     * Attach the name once we know what it is.
     */
    @Restricted(NoExternalUse.class)
    /*package*/ void rename(@Nonnull String newName) {
        if (Util.fixEmptyAndTrim(newName) == null) throw new IllegalArgumentException();
        synchronized (id) {
            name = newName;
        }
    }

    @Restricted(NoExternalUse.class) // Stapler only
    public PhaseExecution getPhase(@Nonnull String phaseName) {
        Phase phase = Phase.valueOf(phaseName);
        return getPhaseExecution(phase);
    }

    public boolean isFor(Id id) {
        return id.fingerprint == this.id.fingerprint;
    }

    @Override
    public String toString() {
        return id.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!o.getClass().equals(getClass())) return false;
        ProvisioningActivity rhs = (ProvisioningActivity) o;
        return id == rhs.id;
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31;
    }
}