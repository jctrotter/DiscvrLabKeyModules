package org.labkey.sequenceanalysis.pipeline;

import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.pipeline.WorkDirectoryTask;
import org.labkey.api.util.FileType;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceLibraryStep;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.sequenceanalysis.SequenceReadsetImpl;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * User: bimber
 * Date: 12/15/12
 * Time: 8:34 PM
 *
 * This task is designed to create the reference FASTA, which requires the DB.  this task will run
 * on the webserver
 */
public class AlignmentInitTask extends WorkDirectoryTask<AlignmentInitTask.Factory>
{
    private static final String ACTIONNAME = "Preparing Run";
    public static final String REFERENCE_DB_FASTA = "Reference FASTA";
    public static final String ID_KEY_FILE = "Reference Id Key";
    public static final String REFERENCE_DB_FASTA_OUTPUT = "Reference Output";
    public static final String COPY_LOCALLY = "copyGenomeLocally";

    private SequenceTaskHelper _taskHelper;

    protected AlignmentInitTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractSequenceTaskFactory<Factory>
    {
        public Factory()
        {
            super(AlignmentInitTask.class);
            setJoin(true);
        }

        @Override
        public boolean isParticipant(PipelineJob job)
        {
            //note: this must be included because this is now how we cache readsets
            //consider moving this to sequence job?
            return true;
        }

        public List<FileType> getInputTypes()
        {
            return Collections.emptyList();
        }

        public String getStatusName()
        {
            return ACTIONNAME.toUpperCase();
        }

        public List<String> getProtocolActionNames()
        {
            return Arrays.asList(ACTIONNAME);
        }

        public PipelineJob.Task createTask(PipelineJob job)
        {
            AlignmentInitTask task = new AlignmentInitTask(this, job);

            return task;
        }

        public boolean isJobComplete(PipelineJob job)
        {
            return false;
        }
    }

    private SequenceTaskHelper getHelper()
    {
        return _taskHelper;
    }

    public RecordedActionSet run() throws PipelineJobException
    {
        SequenceAnalysisJob pipelineJob = getJob().getJobSupport(SequenceAnalysisJob.class);
        _taskHelper = new SequenceTaskHelper(getJob(), _wd);

        for (SequenceReadsetImpl rs : getHelper().getSettings().getReadsets(getJob().getJobSupport(SequenceAnalysisJob.class)))
        {
            pipelineJob.cacheReadset(rs);
        }

        getHelper().cacheExpDatasForParams();

        //build reference if needed
        if (SequenceTaskHelper.isAlignmentUsed(getJob()))
        {
            RecordedAction action = new RecordedAction(ACTIONNAME);
            List<PipelineStepProvider<ReferenceLibraryStep>> providers = SequencePipelineService.get().getSteps(getJob(), ReferenceLibraryStep.class);
            if (providers.isEmpty())
            {
                throw new PipelineJobException("No reference library type was supplied");
            }
            else if (providers.size() > 1)
            {
                throw new PipelineJobException("More than 1 reference library type was supplied");
            }
            else
            {
                getHelper().getFileManager().addInput(action, "Job Parameters", getHelper().getSupport().getParametersFile());
                getJob().getLogger().info("Creating Reference Library FASTA");

                ReferenceLibraryStep step = providers.get(0).create(getHelper());

                //ensure the FASTA exists
                File sharedDirectory = new File(getHelper().getSupport().getAnalysisDirectory(), SequenceTaskHelper.SHARED_SUBFOLDER_NAME);
                if (!sharedDirectory.exists())
                {
                    sharedDirectory.mkdirs();
                }

                ReferenceLibraryStep.Output output = step.createReferenceFasta(sharedDirectory);
                File refFasta = output.getReferenceGenome().getSourceFastaFile();
                if (!refFasta.exists())
                {
                    throw new PipelineJobException("Reference file does not exist: " + refFasta.getPath());
                }

                pipelineJob.setReferenceGenome(output.getReferenceGenome());

                getHelper().getFileManager().addStepOutputs(action, output);
                getHelper().getFileManager().cleanup();
            }

            return new RecordedActionSet(action);
        }

        return new RecordedActionSet();
    }
}