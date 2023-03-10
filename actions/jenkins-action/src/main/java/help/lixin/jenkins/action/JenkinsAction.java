package help.lixin.jenkins.action;

import com.cdancy.jenkins.rest.domain.common.IntegerResponse;
import com.cdancy.jenkins.rest.domain.common.RequestStatus;
import com.cdancy.jenkins.rest.domain.job.Artifact;
import com.cdancy.jenkins.rest.domain.job.BuildInfo;
import com.cdancy.jenkins.rest.domain.job.JobInfo;
import com.cdancy.jenkins.rest.domain.job.ProgressiveText;
import com.fasterxml.jackson.databind.ObjectMapper;
import help.lixin.core.artifact.ArtifactInfo;
import help.lixin.core.constants.Constant;
import help.lixin.core.exception.jenkins.JenkinsTemplateNotFoundException;
import help.lixin.core.pipeline.action.Action;
import help.lixin.core.pipeline.ctx.PipelineContext;
import help.lixin.jenkins.model.CreateJobContext;
import help.lixin.jenkins.model.TriggerBuildContext;
import help.lixin.jenkins.properties.JenkinsProperties;
import help.lixin.jenkins.service.IJobService;
import help.lixin.jenkins.service.JenkinsFaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class JenkinsAction implements Action {
    private Logger logger = LoggerFactory.getLogger(JenkinsAction.class);

    private static final String JOB_NAME = "JOB_NAME";
    private static final String _JENKINS = "_jenkins";

    private static final String FAILURE = "FAILURE";
    private static final String SUCCESS = "SUCCESS";

    public static final String JENKINS_ACTION = "jenkins";

    public JenkinsFaceService jenkinsFaceService;

    private AtomicInteger incr = new AtomicInteger(0);

    private ExecutorService executor = Executors.newFixedThreadPool(100, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("jenkins-" + incr.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public JenkinsAction(JenkinsFaceService jenkinsFaceService) {
        this.jenkinsFaceService = jenkinsFaceService;
    }

    @Override
    public boolean execute(PipelineContext ctx) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("start execute action: [{}],ctx:[{}]", this.getClass().getName(), ctx);
        }

        // ????????????
        String stageParams = ctx.getStageParams();
        ObjectMapper mapper = new ObjectMapper();
        JenkinsActionParams actionParams = mapper.readValue(stageParams, JenkinsActionParams.class);

        // ???????????????
        String projectName = (String) ctx.getVar("projectName");
        String branch = (String) ctx.getVar("branch");
        String url = (String) ctx.getVar("url");

        IJobService jobService = jenkinsFaceService.getJobService();
        // ???jenkins??????job?????????:????????????__????????????
        String jobName = String.format("%s__%s", projectName, branch);

        CompletableFuture<JobInfo> jobInfoCompletableFuture = CompletableFuture.supplyAsync(() -> { // 1. ????????????
            Map<String, Object> tempContext = new HashMap<>(ctx.getVars());
            tempContext.put(JOB_NAME, jobName);
            tempContext.put(_JENKINS, actionParams);
            // ??????????????????????????????
            String template = null;
            try {
                template = jenkinsFaceService.getJenkinsTemplateLoadFaceService().loadAndProcess(actionParams, tempContext);
            } catch (JenkinsTemplateNotFoundException e) {
                throw new RuntimeException(e);
            }
            return template;
        }, executor).thenApplyAsync(template -> { // 2. ??????job???????????????????????????job
            // ??????JobInfo?????????Jenkins????????????????????????:JobInfo
            JobInfo jobInfo = getOrCreateJobInfo(jobName, template);
            return jobInfo;
        }, executor).thenApplyAsync(jobInfo -> { // 3. ???????????????
            int buildNumber = jobInfo.nextBuildNumber();
            ctx.getVars().put(Constant.Jenkins.BUILD_NUMBER, buildNumber);
            if (logger.isDebugEnabled()) {
                logger.debug("trigger build job stage,setting ctx key:[{}],value:[{}]", Constant.Jenkins.BUILD_NUMBER, ctx.getVars().get(Constant.Jenkins.BUILD_NUMBER));
            }
            return jobInfo;
        }).thenApplyAsync(jobInfo -> { // 4. ????????????
            //  ????????????
            triggerBuild(jobName, branch, url);
            return jobInfo;
        }, executor);

        // 5. ????????????????????????(????????????????????????)
        CompletableFuture<BuildInfo> buildInfoCompletableFuture = jobInfoCompletableFuture.thenApplyAsync(jobInfo -> {
            // FAILURE / SUCCESS
            BuildInfo buildInfo = null;
            int i = 1;
            do {
                if (null == buildInfo) {
                    try {
                        // ???????????????????????????????????????????????????
                        TimeUnit.SECONDS.sleep(30);
                    } catch (InterruptedException e) {
                    }
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("start while wait build finish[{}]...", i);
                }

                buildInfo = getBuildInfo(jobName, jobInfo.nextBuildNumber());

                if (logger.isDebugEnabled()) {
                    logger.debug("end while wait build finish[{}]...", i);
                }
                i++;
            } while (null == buildInfo || null == buildInfo.result());

            // ??????????????????,??????:result???????????????,????????????build???????????????,???????????????????????????,??????result????????????
            if ("FAILURE".equals(buildInfo.result())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("trigger build FAIL,fail details:[{}]", buildInfo);
                    throw new RuntimeException();
                }
            }
            return buildInfo;
        }, executor);

        // 6.1 ???????????????,???????????????:??????
        CompletableFuture<BuildInfo> artifactDownloadFuture = buildInfoCompletableFuture.thenApplyAsync(buildInfo -> {
            JenkinsProperties jenkinsProperties = jenkinsFaceService.getJenkinsProperties();
            String localDiskStoreArtifactDir = jenkinsProperties.getArtifactPath();
            // ???????????????
            List<Artifact> artifacts = buildInfo.artifacts();
            if (null != artifacts && artifacts.size() > 1) {
                throw new RuntimeException();
            }

            try {
                jenkinsFaceService.getDownloadStrategyService()
                        //
                        .download(actionParams.getDownloadStrategy(),
                                //
                                localDiskStoreArtifactDir,
                                //
                                jobName,
                                //
                                (int) ctx.getVar(Constant.Jenkins.BUILD_NUMBER),
                                //
                                artifacts.get(0),
                                //
                                new Consumer<String>() {
                                    @Override
                                    public void accept(String artifactDiskFullPath) {
                                        ArtifactInfo artifactInfo = new ArtifactInfo();
                                        artifactInfo.setArtifactFullName(artifactDiskFullPath);

                                        ctx.getVars().put(Constant.Artifact.ARTIFACT_DIR, artifactInfo.getArtifactDir());
                                        ctx.getVars().put(Constant.Artifact.ARTIFACT_NAME, artifactInfo.getArtifactFileName());
                                        ctx.getVars().put(Constant.Artifact.ARTIFACT_FULL_PATH, artifactInfo.getArtifactFullName());

                                        if (logger.isDebugEnabled()) {
                                            logger.debug("download artifact stage,setting ctx key:[{}],value:[{}]", Constant.Artifact.ARTIFACT_DIR, ctx.getVars().get(Constant.Artifact.ARTIFACT_DIR));
                                            logger.debug("download artifact stage,setting ctx key:[{}],value:[{}]", Constant.Artifact.ARTIFACT_NAME, ctx.getVars().get(Constant.Artifact.ARTIFACT_NAME));
                                            logger.debug("download artifact stage,setting ctx key:[{}],value:[{}]", Constant.Artifact.ARTIFACT_FULL_PATH, ctx.getVars().get(Constant.Artifact.ARTIFACT_FULL_PATH));
                                        }
                                    }
                                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return buildInfo;
        }, executor);

        // 6.1 ??????:???????????????????????????????????????????????????,??????,????????????BuildInfo?????????,????????????????????????,?????????????????????,??????????????????,Jenkins????????????Hold??????
        CompletableFuture<ProgressiveText> fetchBuildLogFuture = buildInfoCompletableFuture.thenApplyAsync((buildInfo) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("start fetch build:[{}] log.", buildInfo);
            }

            int nextNumber = (int) ctx.getVar(Constant.Jenkins.BUILD_NUMBER);
            ProgressiveText progressiveText = jobService.lookBuildLog(null, jobName, nextNumber, 0);
            if (null != progressiveText.text()) {
                // TOOD lixin
                // ?????????????????????DB???.
            }

            if (logger.isDebugEnabled()) {
                logger.debug("end fetch build:[{}] log,log content:\n{}", buildInfo, progressiveText.text());
            }
            return progressiveText;
        }, executor);

        // ????????????
        CompletableFuture.allOf(jobInfoCompletableFuture,
                        //
                        buildInfoCompletableFuture,
                        //
                        artifactDownloadFuture,
                        //
                        fetchBuildLogFuture)
                //
                .get(30, TimeUnit.MINUTES);

        // DockerFile
        String dockerFilePath = getDockerFilePath(actionParams);
        if (null != dockerFilePath) {
            ctx.getVars().put(Constant.Docker.DOCKER_FILE, dockerFilePath);
        }
        logger.debug("end execute action: [{}],ctx:[{}]", this.getClass().getName(), ctx);
        return true;
    }

    protected String getDockerFilePath(JenkinsActionParams params) {
        String dockerFile = params.getDockerFile();
        if (null == dockerFile) {
            dockerFile = jenkinsFaceService.getJenkinsProperties().getDockerFile();
        }
        return dockerFile;
    }


    protected IntegerResponse triggerBuild(String jobName, String branch, String url) {
        IJobService jobService = jenkinsFaceService.getJobService();
        TriggerBuildContext buildContext = TriggerBuildContext.newBuilder()
                //
                .jobName(jobName)
                //
                .properties("branch", branch)
                //
                .properties("url", url)
                //
                .build();
        if (logger.isDebugEnabled()) {
            logger.debug("start trigger job:[{}],trigger ctx:[{}]", buildContext.getJobName(), buildContext);
        }
        IntegerResponse response = jobService.triggerBuild(buildContext);
        if (logger.isDebugEnabled()) {
            logger.debug("end trigger job:[{}],trigger ctx:[{}]", buildContext.getJobName(), buildContext);
        }
        return response;
    }

    protected JobInfo getOrCreateJobInfo(String jobName, String template) throws RuntimeException {
        IJobService jobService = jenkinsFaceService.getJobService();
        // 1. ???lookup jobName exists
        JobInfo jobInfo = jobService.getJobInfo(null, jobName);
        if (null == jobInfo) { // jobName???jenkins????????????
            CreateJobContext createJobContext = CreateJobContext.newBuilder()
                    //
                    .jobName(jobName)
                    //
                    .configXML(template)
                    //
                    .build();
            if (logger.isDebugEnabled()) {
                logger.debug("create job stage,start create job name:[{}],xml conent:\n{}", createJobContext.getJobName(), createJobContext.getConfigXML());
            }
            RequestStatus status = jobService.createJob(createJobContext);
            if (status.errors().size() > 0) {
                // ??????????????????
                throw new RuntimeException();
            } else {
                // 3.2 ?????????job???,????????????job??????
                jobInfo = jobService.getJobInfo(null, jobName);
                if (logger.isDebugEnabled()) {
                    logger.debug("create job stage,end create job name:[{}],jobinfo:[{}]", createJobContext.getJobName(), jobInfo);
                }
            }
        }
        return jobInfo;
    }


    protected BuildInfo getBuildInfo(String jobName, int buildNumber) {
        IJobService jobService = jenkinsFaceService.getJobService();
        return jobService.getBuildInfo(null, jobName, buildNumber);
    }

    @Override
    public String name() {
        return JENKINS_ACTION;
    }
}
