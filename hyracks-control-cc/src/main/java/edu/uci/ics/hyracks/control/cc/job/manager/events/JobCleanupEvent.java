/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.control.cc.job.manager.events;

import java.util.Set;
import java.util.UUID;

import edu.uci.ics.hyracks.api.exceptions.HyracksException;
import edu.uci.ics.hyracks.api.job.JobStatus;
import edu.uci.ics.hyracks.control.cc.ClusterControllerService;
import edu.uci.ics.hyracks.control.cc.NodeControllerState;
import edu.uci.ics.hyracks.control.cc.application.CCApplicationContext;
import edu.uci.ics.hyracks.control.cc.job.JobRun;
import edu.uci.ics.hyracks.control.cc.jobqueue.AbstractEvent;
import edu.uci.ics.hyracks.control.cc.remote.RemoteRunner;
import edu.uci.ics.hyracks.control.cc.remote.ops.JobCompleteNotifier;

public class JobCleanupEvent extends AbstractEvent {
    private ClusterControllerService ccs;
    private UUID jobId;
    private JobStatus status;
    private Exception exception;

    public JobCleanupEvent(ClusterControllerService ccs, UUID jobId, JobStatus status, Exception exception) {
        this.ccs = ccs;
        this.jobId = jobId;
        this.status = status;
        this.exception = exception;
    }

    @Override
    public void run() {
        final JobRun run = ccs.getRunMap().get(jobId);
        Set<String> targetNodes = run.getParticipatingNodeIds();
        final JobCompleteNotifier[] jcns = new JobCompleteNotifier[targetNodes.size()];
        int i = 0;
        for (String n : targetNodes) {
            jcns[i++] = new JobCompleteNotifier(n, jobId);
            NodeControllerState ncs = ccs.getNodeMap().get(n);
            if (ncs != null) {
                ncs.getActiveJobIds().remove(jobId);
            }
        }
        ccs.getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                if (jcns.length > 0) {
                    try {
                        RemoteRunner.runRemote(ccs, jcns, null);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                ccs.getJobQueue().schedule(new AbstractEvent() {
                    @Override
                    public void run() {
                        CCApplicationContext appCtx = ccs.getApplicationMap().get(
                                run.getJobActivityGraph().getApplicationName());
                        if (appCtx != null) {
                            try {
                                appCtx.notifyJobFinish(jobId);
                            } catch (HyracksException e) {
                                e.printStackTrace();
                            }
                        }
                        run.setStatus(status, exception);
                    }
                });
            }
        });
    }
}