package org.labkey.sequenceanalysis.run.alignment;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.util.FileUtil;
import org.labkey.sequenceanalysis.api.pipeline.AbstractAlignmentStepProvider;
import org.labkey.sequenceanalysis.api.pipeline.AlignmentStep;
import org.labkey.sequenceanalysis.api.pipeline.PipelineContext;
import org.labkey.sequenceanalysis.api.pipeline.PipelineStepProvider;
import org.labkey.sequenceanalysis.api.pipeline.ReferenceGenome;
import org.labkey.sequenceanalysis.api.pipeline.SequencePipelineService;
import org.labkey.sequenceanalysis.api.run.AbstractCommandPipelineStep;
import org.labkey.sequenceanalysis.api.run.AbstractCommandWrapper;
import org.labkey.sequenceanalysis.api.run.CommandLineParam;
import org.labkey.sequenceanalysis.api.run.ToolParameterDescriptor;
import org.labkey.sequenceanalysis.run.util.SamFormatConverterWrapper;
import org.labkey.sequenceanalysis.util.FastqToFastaConverter;
import org.labkey.sequenceanalysis.util.SequenceUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: bimber
 * Date: 12/16/12
 * Time: 11:16 AM
 */
public class LastzWrapper extends AbstractCommandWrapper
{
    public LastzWrapper(@Nullable Logger logger)
    {
        super(logger);
    }

    public static class LastzAlignmentStep extends AbstractCommandPipelineStep<LastzWrapper> implements AlignmentStep
    {
        public LastzAlignmentStep(PipelineStepProvider provider, PipelineContext ctx)
        {
            super(provider, ctx, new LastzWrapper(ctx.getLogger()));
        }

        @Override
        public IndexOutput createIndex(ReferenceGenome referenceGenome, File outputDir) throws PipelineJobException
        {
            return new IndexOutputImpl(referenceGenome);
        }

        @Override
        public AlignmentOutput performAlignment(File inputFastq1, @Nullable File inputFastq2, File outputDirectory, ReferenceGenome referenceGenome, String basename) throws PipelineJobException
        {
            AlignmentOutputImpl output = new AlignmentOutputImpl();

            List<File> fastqs = new ArrayList<>();
            if (inputFastq1 != null)
                    fastqs.add(inputFastq1);
            if (inputFastq2 != null)
                fastqs.add(inputFastq2);

            File tempFasta = getWrapper().convertInputFastqToFasta(outputDirectory, fastqs);
            output.addInput(tempFasta, "Converted FASTA");
            output.addIntermediateFile(tempFasta);

            File outputBam = getWrapper().doAlignment(tempFasta, referenceGenome.getWorkingFastaFile(), outputDirectory, basename, getClientCommandArgs());
            output.addOutput(outputBam, AlignmentOutputImpl.BAM_ROLE);

            return output;
        }

        @Override
        public boolean doSortCleanBam()
        {
            return false;
        }
    }

    public static class Provider extends AbstractAlignmentStepProvider<AlignmentStep>
    {
        public Provider()
        {
            super("Lastz", "Lastz has performed well for both sequence-based genotyping and viral analysis.  A considerable downside is that Lastz discard quality scores, so other aligners may be a better choice.", Arrays.asList(
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--identity"), "identity", "Min Pct Identity", "The minimum percent identity required per alignment for that match to be included", "ldk-numberfield", null, 98),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--continuity"), "continuity", "Percent Continuity", "Continuity is the percentage of alignment columns that are not gaps. Alignment blocks outside the given range are discarded.", "ldk-numberfield", null, 90)
            ), null, "http://www.bx.psu.edu/~rsharris/lastz/", false);
        }

        public LastzAlignmentStep create(PipelineContext context)
        {
            return new LastzAlignmentStep(this, context);
        }
    }

    public File doAlignment(File inputFasta, File refFasta, File outputDirectory, String basename, List<String> options) throws PipelineJobException
    {
        getLogger().info("Running LASTZ Alignment");

        List<String> args = new ArrayList<>();
        args.add(getExe().getPath());

        long refCount = SequenceUtil.getLineCount(refFasta) / 2;
        args.add(refFasta.getPath() + (refCount > 1 ? "[multiple]" : ""));
        args.add(inputFasta.getPath());

        if (options != null)
            args.addAll(options);

        File sam = new File(outputDirectory, basename + ".sam");
        args.add("--format=softsam");
        args.add("--output=" + sam.getPath());

        execute(args);

        //convert to BAM
        File bam = new File(outputDirectory, basename + ".bam");
        bam = new SamFormatConverterWrapper(getLogger()).execute(sam, bam, true);
        if (!bam.exists())
        {
            throw new PipelineJobException("Unable to find output file: " + bam.getPath());
        }

        return bam;
    }

    public File convertInputFastqToFasta(File outputDirectory, List<File> inputFastqs) throws PipelineJobException
    {
        FastqToFastaConverter converter = new FastqToFastaConverter(getLogger());
        File output = new File(outputDirectory, FileUtil.getBaseName(inputFastqs.get(0)) + ".fasta");
        try
        {
            converter.execute(output, inputFastqs);
        }
        catch (Exception e)
        {
            throw new PipelineJobException(e);
        }

        return output;
    }

    protected File getExe()
    {
        return SequencePipelineService.get().getExeForPackage("LASTZPATH", "lastz");
    }
}