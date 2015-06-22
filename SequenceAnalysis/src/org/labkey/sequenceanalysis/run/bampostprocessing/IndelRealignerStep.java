package org.labkey.sequenceanalysis.run.bampostprocessing;

import org.json.JSONObject;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.util.FileUtil;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.BamProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.sequenceanalysis.run.util.IndelRealignerWrapper;

import java.io.File;
import java.util.Arrays;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 4:59 PM
 */
public class IndelRealignerStep extends AbstractCommandPipelineStep<IndelRealignerWrapper> implements BamProcessingStep
{
    public IndelRealignerStep(PipelineStepProvider provider, PipelineContext ctx)
    {
        super(provider, ctx, new IndelRealignerWrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractPipelineStepProvider<IndelRealignerStep>
    {
        public Provider()
        {
            super("IndelRealigner", "Indel Realigner", "GATK", "The step runs GATK's IndelRealigner tool.  This tools performs local realignment to minmize the number of mismatching bases across all the reads.", Arrays.asList(
                    ToolParameterDescriptor.create("useQueue", "Use Queue?", "If checked, this tool will attempt to run using GATK queue, allowing parallelization using scatter/gather.", "checkbox", new JSONObject()
                    {{
                        put("checked", false);
                    }}, false)
            ), null, "http://www.broadinstitute.org/gatk/gatkdocs/org_broadinstitute_sting_gatk_walkers_indels_IndelRealigner.html");
        }

        @Override
        public IndelRealignerStep create(PipelineContext ctx)
        {
            return new IndelRealignerStep(this, ctx);
        }
    }

    @Override
    public Output processBam(Readset rs, File inputBam, ReferenceGenome referenceGenome, File outputDirectory) throws PipelineJobException
    {
        BamProcessingOutputImpl output = new BamProcessingOutputImpl();
        getWrapper().setOutputDir(outputDirectory);

        File dictionary = new File(referenceGenome.getWorkingFastaFile().getParentFile(), FileUtil.getBaseName(referenceGenome.getWorkingFastaFile().getName()) + ".dict");
        boolean dictionaryExists = dictionary.exists();
        getPipelineCtx().getLogger().debug("dict exists: " + dictionaryExists + ", " + dictionary.getPath());

        File outputBam = new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".realigned.bam");
        if (getProvider().getParameterByName("useQueue").extractValue(getPipelineCtx().getJob(), getProvider(), Boolean.class, false))
        {
            output.setBAM(getWrapper().executeWithQueue(inputBam, outputBam, referenceGenome.getWorkingFastaFile(), null));
        }
        else
        {
            output.setBAM(getWrapper().execute(inputBam, outputBam, referenceGenome.getWorkingFastaFile(), null));
        }

        output.addIntermediateFile(outputBam);
        output.addIntermediateFile(getWrapper().getExpectedIntervalsFile(inputBam), "Realigner Intervals File");

        if (!dictionaryExists)
        {
            if (dictionary.exists())
            {
                output.addIntermediateFile(dictionary);
            }
            else
            {
                getPipelineCtx().getLogger().debug("dict file not found: " + dictionary.getPath());
            }
        }

        //note: we might sort the input
        File sortedBam = new File(inputBam.getParentFile(), FileUtil.getBaseName(inputBam) + ".sorted.bam");
        if (sortedBam.exists())
        {
            getPipelineCtx().getLogger().debug("sorted file exists: " + sortedBam.getPath());
            output.addIntermediateFile(sortedBam);
            output.addIntermediateFile(new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".realigned.bai"));
        }
        else
        {
            getPipelineCtx().getLogger().debug("sorted file does not exist: " + sortedBam.getPath());
            output.addIntermediateFile(new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".realigned.bai"));
        }

        return output;
    }
}
