package org.labkey.sequenceanalysis.run.alignment;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.pipeline.CommandLineParam;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.util.FileUtil;
import org.labkey.api.sequenceanalysis.pipeline.AbstractAlignmentStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentStep;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.sequenceanalysis.run.util.SamFormatConverterWrapper;
import org.labkey.sequenceanalysis.util.SequenceUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: bimber
 * Date: 6/14/2014
 * Time: 8:35 AM
 */
public class BWAMemWrapper extends BWAWrapper
{
    public BWAMemWrapper(@Nullable Logger logger)
    {
        super(logger);
    }

    public static class BWAMemAlignmentStep extends BWAAlignmentStep
    {
        public BWAMemAlignmentStep(PipelineStepProvider provider, PipelineContext ctx)
        {
            super(provider, ctx);
        }

        @Override
        protected AlignmentOutput _performAlignment(AlignmentOutputImpl output, File inputFastq1, @Nullable File inputFastq2, File outputDirectory, ReferenceGenome referenceGenome, String basename) throws PipelineJobException
        {
            getWrapper().setOutputDir(outputDirectory);

            getPipelineCtx().getLogger().info("Running BWA-Mem");

            List<String> args = new ArrayList<>();
            args.add(getWrapper().getExe().getPath());
            args.add("mem");
            args.addAll(getClientCommandArgs());
            getWrapper().appendThreads(getPipelineCtx().getJob(), args);

            args.add(new File(referenceGenome.getAlignerIndexDir("bwa"), FileUtil.getBaseName(referenceGenome.getWorkingFastaFile().getName()) + ".bwa.index").getPath());
            args.add(inputFastq1.getPath());

            if (inputFastq2 != null)
            {
                args.add(inputFastq2.getPath());
            }

            File sam = new File(outputDirectory, basename + ".sam");
            getWrapper().execute(args, sam);
            if (!sam.exists() || !SequenceUtil.hasMinLineCount(sam, 2))
            {
                throw new PipelineJobException("SAM file doesnt exist or has too few lines: " + sam.getPath());
            }

            //convert to BAM
            File bam = new File(outputDirectory, basename + ".bam");
            SamFormatConverterWrapper converter = new SamFormatConverterWrapper(getPipelineCtx().getLogger());
            //converter.setStringency(SAMFileReader.ValidationStringency.SILENT);
            bam = converter.execute(sam, bam, true);
            if (!bam.exists())
            {
                throw new PipelineJobException("Unable to find output file: " + bam.getPath());
            }

            output.addOutput(bam, AlignmentOutputImpl.BAM_ROLE);

            return output;
        }
    }

    public static class Provider extends AbstractAlignmentStepProvider<AlignmentStep>
    {
        public Provider()
        {
            super("BWA-Mem", null, Arrays.asList(
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("-a"), "outputAll", "Output All Hits", "Output all found alignments for single-end or unpaired paired-end reads. These alignments will be flagged as secondary alignments.", "checkbox", new JSONObject(){{
                        put("checked", false);
                    }}, true),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("-M"), "markSplit", "Mark Shorter Hits As Secondary", "Mark shorter split hits as secondary (for Picard compatibility).", "checkbox", new JSONObject(){{
                        put("checked", true);
                    }}, true)
            ), null, "http://bio-bwa.sourceforge.net/", true, true);
        }

        public BWAMemAlignmentStep create(PipelineContext context)
        {
            return new BWAMemAlignmentStep(this, context);
        }
    }
}
