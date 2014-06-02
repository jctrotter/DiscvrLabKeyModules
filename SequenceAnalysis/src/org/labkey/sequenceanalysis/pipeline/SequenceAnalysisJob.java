package org.labkey.sequenceanalysis.pipeline;

import org.labkey.api.pipeline.PipeRoot;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.TaskId;
import org.labkey.api.pipeline.TaskPipeline;
import org.labkey.api.pipeline.file.AbstractFileAnalysisJob;
import org.labkey.api.pipeline.file.AbstractFileAnalysisProtocol;
import org.labkey.api.pipeline.file.FileAnalysisTaskPipeline;
import org.labkey.api.util.FileType;
import org.labkey.api.util.NetworkDrive;
import org.labkey.api.util.Pair;
import org.labkey.api.view.ViewBackgroundInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 4/27/14
 * Time: 5:41 AM
 */
public class SequenceAnalysisJob extends AbstractFileAnalysisJob
{
    private TaskId _taskPipelineId;
    private AbstractFileAnalysisProtocol _protocol;

    public SequenceAnalysisJob(AbstractFileAnalysisProtocol<AbstractFileAnalysisJob> protocol,
                               ViewBackgroundInfo info,
                               PipeRoot root,
                               TaskId taskPipelineId,
                               File fileParameters,
                               List<File> filesInput) throws IOException
    {
        super(protocol, "File Analysis", info, root, protocol.getName(), fileParameters, filesInput, true, true);

        _taskPipelineId = taskPipelineId;
    }

    public SequenceAnalysisJob(SequenceAnalysisJob job, List<File> filesInput)
    {
        super(job, filesInput);

        _taskPipelineId = job._taskPipelineId;
    }

    @Override
    public TaskId getTaskPipelineId()
    {
        return _taskPipelineId;
    }

    @Override
    public FileAnalysisTaskPipeline getTaskPipeline()
    {
        TaskPipeline tp = super.getTaskPipeline();

        assert tp != null : "Task pipeline " + _taskPipelineId + " not found.";

        return (FileAnalysisTaskPipeline) tp;
    }

    @Override
    public AbstractFileAnalysisJob createSingleFileJob(File file)
    {
        return new SequenceAnalysisJob(this, Collections.singletonList(file));
    }

    @Override
    public List<PipelineJob> createSplitJobs()
    {
        ArrayList<PipelineJob> jobs = new ArrayList<>();
        if (getActiveTaskFactory().getId().getNamespaceClass().equals(SequenceAlignmentTask.class) && getInputFiles().size() > 1)
        {
            List<Pair<File, File>> files = SequenceAlignmentTask.getAlignmentFiles(this, getInputFiles(), false);
            for (Pair<File, File> pair : files)
            {
                List<File> toRun = new ArrayList<>();
                if (pair.first != null)
                    toRun.add(pair.first);

                if (pair.second != null)
                    toRun.add(pair.second);

                if (!toRun.isEmpty())
                {
                    SequenceAnalysisJob newJob = new SequenceAnalysisJob(this, toRun);
                    newJob.setSplittable(false);
                    jobs.add(newJob);
                }
            }
        }
        else
        {
            for (File file : getInputFiles())
            {
                jobs.add(new SequenceAnalysisJob(this, Collections.singletonList(file)));
            }
        }

        return Collections.unmodifiableList(jobs);
    }

    @Override
    public File findOutputFile(String name)
    {
        return findFile(name);
    }

    @Override
    public File findInputFile(String name)
    {
        return findFile(name);
    }

    public File findFile(String name)
    {
        File dirAnalysis = getAnalysisDirectory();

        for (Map.Entry<FileType, FileType[]> entry : getTaskPipeline().getTypeHierarchy().entrySet())
        {
            if (entry.getKey().isType(name))
            {
                // TODO: Eventually we will need to actually consult the parameters files
                //       in order to find files.

                // First try to go two directories up
                File dir = dirAnalysis.getParentFile();
                if (dir != null)
                {
                    dir = dir.getParentFile();
                }

                FileType[] derivedTypes = entry.getValue();
                for (int i = derivedTypes.length - 1; i >= 0; i--)
                {
                    // Go two directories up for each level of derivation
                    if (dir != null)
                    {
                        dir = dir.getParentFile();
                    }
                    if (dir != null)
                    {
                        dir = dir.getParentFile();
                    }
                }

                String relativePath = getPipeRoot().relativePath(dir);
                File expectedFile = getPipeRoot().resolvePath(relativePath + "/" + name);

                if (!NetworkDrive.exists(expectedFile))
                {
                    // If the file isn't where we would expect it, check other directories in the same hierarchy
                    File alternateFile = findFileInAlternateDirectory(expectedFile.getParentFile(), dirAnalysis, name);
                    if (alternateFile != null)
                    {
                        // If we found a file that matches, use it
                        return alternateFile;
                    }
                }
                return expectedFile;
            }
        }

        // Path of last resort is always to look in the current directory.
        return new File(dirAnalysis, name);
    }

    /**
     * Starting from the expectedDir, look up the chain until getting to the final directory. Return the first
     * file that matches by name.
     * @param expectedDir where we would have expected the file to be, but it wasn't there
     * @param dir must be a descendant of expectedDir, this is the deepest directory that will be inspected
     * @param name name of the file to look for
     * @return matching file, or null if nothing was found
     */
    private File findFileInAlternateDirectory(File expectedDir, File dir, String name)
    {
        // Bail out if we've gotten all the way down to the originally expected file location
        if (dir == null || dir.equals(expectedDir))
        {
            return null;
        }
        // Recurse through the parent directories to find it in the place closest to the expected directory
        File result = findFileInAlternateDirectory(expectedDir, dir.getParentFile(), name);
        if (result != null)
        {
            // If we found a match, use it
            return result;
        }

        result = new File(dir, name);
        if (NetworkDrive.exists(result))
        {
            return result;
        }
        return null;
    }
}