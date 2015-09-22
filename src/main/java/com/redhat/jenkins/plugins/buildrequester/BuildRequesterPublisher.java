package com.redhat.jenkins.plugins.buildrequester;

import com.redhat.jenkins.plugins.buildrequester.scm.GitRepository;
import com.redhat.jenkins.plugins.buildrequester.scm.Repository;
import com.redhat.jenkins.plugins.buildrequester.scm.SubversionRepository;
import hudson.*;
import hudson.maven.AbstractMavenProject;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author vdedik@redhat.com
 */
public class BuildRequesterPublisher extends Recorder {
    public static final String DEFAULT_URL = "http://newcastle.example.com";

    private BuildRequesterAction action;
    private String url;

    @DataBoundConstructor
    public BuildRequesterPublisher(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean prebuild(AbstractBuild<?,?> build, BuildListener listener) {
        if (build instanceof MavenModuleSetBuild) {
            action = new BuildRequesterAction();
            action.setBuild((MavenModuleSetBuild) build);
            action.setUrl(this.url);
        }
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if (action != null) {
            MavenModuleSetBuild mavenBuild = (MavenModuleSetBuild) build;
            MavenModule rootPom = mavenBuild.getProject().getRootModule();
            File projectDir = new File(build.getWorkspace().getRemote(),
                    mavenBuild.getProject().getRootPOM(build.getEnvironment(listener))).getParentFile();
            Repository repo = getRepository(
                    mavenBuild.getProject().getScm(), listener, build.getEnvironment(listener), projectDir);


            // Set name
            action.setName(rootPom.getArtifactId());

            // Set GAV
            String gav = String.format("%s:%s:%s", rootPom.getGroupId(), rootPom.getArtifactId(),
                    rootPom.getVersion());
            action.setGav(gav);

            // Set SCM Url
            action.setScm(repo.getUrl());

            // Set Tags
            String headCommitId = repo.getHeadCommitId();
            List<String> tags = repo.getTagsByCommitId(headCommitId);
            tags.add(headCommitId);
            action.setTags(tags);

            // Set java version
            Proc proc = launcher.launch()
                    .cmds("$JAVA_HOME/bin/java", "-version")
                    .envs(mavenBuild.getEnvironment(listener))
                    .readStdout()
                    .start();
            if (proc.join() == 0) {
                String javaVersionOut = IOUtils.toString(proc.getStdout(), build.getCharset());
                Pattern pattern = Pattern.compile("java version \"(.*)\"");
                Matcher matcher = pattern.matcher(javaVersionOut);
                if (matcher.find()) {
                    action.setJavaVersion(matcher.group(1));
                }
            }

            // Set Maven Version
            action.setMavenVersion(mavenBuild.getMavenVersionUsed());

            // Set Maven Command
            action.setBuildCommand("mvn " + mavenBuild.getProject().getGoals());

            // Set Command Line Params (MAVEN_OPTS)
            action.setCommandLineParameters(mavenBuild.getProject().getMavenOpts());

            mavenBuild.addAction(action);
        }
        return true;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public String getDefaultUrl() {
            return BuildRequesterPublisher.DEFAULT_URL;
        }

        @Override
        public BuildRequesterPublisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(BuildRequesterPublisher.class, formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return AbstractMavenProject.class.isAssignableFrom(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Configure Handover to Productization";
        }
    }

    private Repository getRepository(SCM scm, TaskListener taskListener, EnvVars envVars, File repoDir) {
        Repository repository = null;
        if (scm instanceof GitSCM) {
            repository = new GitRepository(taskListener, envVars, repoDir);
        } else if (scm instanceof SubversionSCM) {
            repository = new SubversionRepository(repoDir);
        }

        return repository;
    }

}
