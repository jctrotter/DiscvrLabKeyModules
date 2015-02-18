package org.labkey.sequenceanalysis.pipeline;

import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.pipeline.WorkDirectoryTask;
import org.labkey.api.util.FileType;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentStep;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceLibraryStep;
import org.labkey.sequenceanalysis.run.util.FastaIndexer;

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
public class PrepareAlignerIndexesTask extends WorkDirectoryTask<PrepareAlignerIndexesTask.Factory>
{
    private static final String ALIGNER_INDEXES_ACTIONNAME = "Preparing Aligner Indexes";

    private SequenceTaskHelper _taskHelper;

    protected PrepareAlignerIndexesTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractSequenceTaskFactory<Factory>
    {
        public Factory()
        {
            super(PrepareAlignerIndexesTask.class);
            setJoin(true);
        }

        @Override
        public boolean isParticipant(PipelineJob job)
        {
            return SequenceTaskHelper.isAlignmentUsed(job);
        }

        public List<FileType> getInputTypes()
        {
            return Collections.emptyList();
        }

        public String getStatusName()
        {
            return ALIGNER_INDEXES_ACTIONNAME.toUpperCase();
        }

        public List<String> getProtocolActionNames()
        {
            return Arrays.asList(ALIGNER_INDEXES_ACTIONNAME);
        }

        public PipelineJob.Task createTask(PipelineJob job)
        {
            PrepareAlignerIndexesTask task = new PrepareAlignerIndexesTask(this, job);

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
        PipelineJob job = getJob();
        _taskHelper = new SequenceTaskHelper(job, _wd);

        RecordedAction action = ensureIndexExists();

        return new RecordedActionSet(action);
    }

    private RecordedAction ensureIndexExists() throws PipelineJobException
    {
        getJob().getLogger().info(ALIGNER_INDEXES_ACTIONNAME);

        RecordedAction action = new RecordedAction(ALIGNER_INDEXES_ACTIONNAME);

        ReferenceLibraryStep libraryStep = getHelper().getSingleStep(ReferenceLibraryStep.class).create(getHelper());
        getJob().getLogger().debug("using reference type: " + libraryStep.getProvider().getLabel());

        AlignmentStep alignmentStep = getHelper().getSingleStep(AlignmentStep.class).create(getHelper());

        ReferenceGenome referenceGenome = getHelper().getSequenceSupport().getReferenceGenome();
        if (referenceGenome == null)
        {
            throw new PipelineJobException("No reference genome was cached prior to preparing aligned indexes");
        }

        File refFasta = referenceGenome.getSourceFastaFile();
        if (!refFasta.exists())
        {
            throw new PipelineJobException("Reference fasta does not exist: " + refFasta.getPath());
        }
        getHelper().getFileManager().addInput(action, ReferenceLibraryTask.REFERENCE_DB_FASTA, refFasta);

        FastaIndexer indexer = new FastaIndexer(getJob().getLogger());
        File refFastaIndex = indexer.getExpectedIndexName(refFasta);
        if (!refFastaIndex.exists())
        {
            indexer.execute(refFasta);
            getHelper().getFileManager().addOutput(action, "Reference DB FASTA Index", refFastaIndex);
        }

        getJob().getLogger().debug("location of source FASTA: " + getHelper().getSequenceSupport().getReferenceGenome().getSourceFastaFile().getPath());

        //NOTE: always create the index back in the local working dir, since we'll need to move it back there anyway
        File localSharedDirectory = new File(getHelper().getSupport().getAnalysisDirectory(), SequenceTaskHelper.SHARED_SUBFOLDER_NAME);
        if (!localSharedDirectory.exists())
        {
            localSharedDirectory.mkdirs();
        }
        getJob().getLogger().debug("indexes will be created in: " + localSharedDirectory.getPath());

        AlignmentStep.IndexOutput output = alignmentStep.createIndex(referenceGenome, localSharedDirectory);
        getHelper().getFileManager().addStepOutputs(action, output);

        return action;
    }
}
