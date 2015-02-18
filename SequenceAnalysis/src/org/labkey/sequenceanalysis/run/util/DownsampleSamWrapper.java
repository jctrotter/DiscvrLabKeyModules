package org.labkey.sequenceanalysis.run.util;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.util.FileUtil;
import org.labkey.sequenceanalysis.run.preprocessing.DownsampleFastqWrapper;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

/**
 * User: bimber
 * Date: 10/28/12
 * Time: 10:28 PM
 */
public class DownsampleSamWrapper extends PicardWrapper
{
    public DownsampleSamWrapper(@Nullable Logger logger)
    {
        super(logger);
    }

    public File execute(File file, Double pctRetained) throws PipelineJobException
    {
        getLogger().info("Downsampling reads: " + file);
        getLogger().info("\tPercent retained: " + pctRetained);
        getLogger().info("\tDownsampleSam version: " + getVersion());

        execute(getParams(file, pctRetained));
        File output = new File(getOutputDir(file), getOutputFilename(file));
        if (!output.exists())
        {
            throw new PipelineJobException("Output file could not be found: " + output.getPath());
        }

        return output;

    }

    private List<String> getParams(File file, Double pctRetained) throws PipelineJobException
    {
        List<String> params = new LinkedList<>();
        params.add("java");
        //params.add("-Xmx4g");
        params.add("-jar");
        params.add(getJar().getPath());
        params.add("INPUT=" + file.getPath());
        params.add("OUTPUT=" + new File(getOutputDir(file), getOutputFilename(file)).getPath());
        params.add("PROBABILITY=" + pctRetained);

        return params;
    }

    public String getOutputFilename(File file)
    {
        return FileUtil.getBaseName(file) + ".downsampled.sam";
    }

    @Override
    protected File getJar()
    {
        return getPicardJar("DownsampleSam.jar");
    }
}
