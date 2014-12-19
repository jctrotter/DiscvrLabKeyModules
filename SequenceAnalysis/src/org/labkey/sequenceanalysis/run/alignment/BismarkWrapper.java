package org.labkey.sequenceanalysis.run.alignment;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.reports.ExternalScriptEngineDefinition;
import org.labkey.api.reports.LabkeyScriptEngineManager;
import org.labkey.api.reports.RScriptEngineFactory;
import org.labkey.api.reports.ReportService;
import org.labkey.api.resource.FileResource;
import org.labkey.api.resource.Resource;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.Pair;
import org.labkey.sequenceanalysis.SequenceAnalysisModule;
import org.labkey.sequenceanalysis.api.model.ReadsetModel;
import org.labkey.sequenceanalysis.api.pipeline.AbstractAlignmentStepProvider;
import org.labkey.sequenceanalysis.api.pipeline.AbstractPipelineStepProvider;
import org.labkey.sequenceanalysis.api.pipeline.AlignmentStep;
import org.labkey.sequenceanalysis.api.pipeline.BamProcessingStep;
import org.labkey.sequenceanalysis.api.pipeline.PipelineContext;
import org.labkey.sequenceanalysis.api.pipeline.PipelineStepProvider;
import org.labkey.sequenceanalysis.api.pipeline.ReferenceGenome;
import org.labkey.sequenceanalysis.api.pipeline.SequencePipelineService;
import org.labkey.sequenceanalysis.api.run.AbstractCommandPipelineStep;
import org.labkey.sequenceanalysis.api.run.AbstractCommandWrapper;
import org.labkey.sequenceanalysis.api.run.CommandLineParam;
import org.labkey.sequenceanalysis.api.run.ToolParameterDescriptor;
import org.labkey.sequenceanalysis.run.bampostprocessing.BamProcessingOutputImpl;
import org.labkey.sequenceanalysis.run.util.SamtoolsRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Created by bimber on 8/9/2014.
 */
public class BismarkWrapper extends AbstractCommandWrapper
{
    private static String CACHED_NAME = "Bisulfite_Genome";

    public BismarkWrapper(@Nullable Logger logger)
    {
        super(logger);
    }

    public static class BismarkAlignmentStep extends AbstractCommandPipelineStep<BismarkWrapper> implements AlignmentStep
    {
        public BismarkAlignmentStep(PipelineStepProvider provider, PipelineContext ctx)
        {
            super(provider, ctx, new BismarkWrapper(ctx.getLogger()));
        }

        @Override
        public AlignmentOutput performAlignment(File inputFastq1, @Nullable File inputFastq2, File outputDirectory, ReferenceGenome referenceGenome, String basename) throws PipelineJobException
        {
            AlignmentOutputImpl output = new AlignmentOutputImpl();

            AlignerIndexUtil.copyIndexIfExists(this.getPipelineCtx(), output, CACHED_NAME);
            BismarkWrapper wrapper = getWrapper();

            List<String> args = new ArrayList<>();
            args.add(wrapper.getExe().getPath());
            args.add(referenceGenome.getWorkingFastaFile().getParentFile().getPath());

            args.add("--samtools_path");
            args.add(new SamtoolsRunner(getPipelineCtx().getLogger()).getSamtoolsPath().getParentFile().getPath());
            args.add("--path_to_bowtie");
            args.add(new BowtieWrapper(getPipelineCtx().getLogger()).getExe().getParentFile().getPath());

            if (getClientCommandArgs() != null)
            {
                args.addAll(getClientCommandArgs());
            }

            args.add("-q"); //input is FASTQ format
            args.add("-o");
            args.add(outputDirectory.getPath());

            //NOTE: only works for bowtie2
//            Integer threads = SequenceTaskHelper.getMaxThreads(getPipelineCtx().getJob());
//            if (threads != null)
//            {
//                args.add("-p"); //multi-threaded
//                args.add(threads.toString());
//            }

            args.add("--bam"); //BAM output

            if (inputFastq2 != null)
            {
                args.add("-1");
                args.add(inputFastq1.getPath());

                args.add("-2");
                args.add(inputFastq2.getPath());
            }
            else
            {
                args.add(inputFastq1.getPath());
            }

            String outputBasename = inputFastq1.getName() + "_bismark" + (inputFastq2 == null ? "" : "_pe");
            File bam = new File(outputDirectory, outputBasename + ".bam");
            getWrapper().setWorkingDir(outputDirectory);
            getWrapper().execute(args);

            output.addOutput(bam, AlignmentOutputImpl.BAM_ROLE);
            output.addOutput(new File(outputDirectory, inputFastq1.getName() + "_bismark_" + (inputFastq2 == null ? "SE" : "PE") + "_report.txt"), "Bismark Summary Report");

            return output;
        }

        @Override
        public boolean doSortCleanBam()
        {
            return false;
        }

        @Override
        public IndexOutput createIndex(ReferenceGenome referenceGenome, File outputDir) throws PipelineJobException
        {
            getPipelineCtx().getLogger().info("Preparing reference for bismark");
            IndexOutputImpl output = new IndexOutputImpl(referenceGenome);

            File indexOutputDir = outputDir;
            File fastaParentDir = referenceGenome.getWorkingFastaFile().getParentFile();
            if (!fastaParentDir.equals(outputDir))
            {
                indexOutputDir = referenceGenome.getWorkingFastaFile().getParentFile();
            }


            File genomeBuild = new File(indexOutputDir, CACHED_NAME);
            boolean hasCachedIndex = AlignerIndexUtil.hasCachedIndex(this.getPipelineCtx(), CACHED_NAME);
            if (!hasCachedIndex)
            {
                List<String> args = new ArrayList<>();
                args.add(getWrapper().getBuildExe().getPath());
                args.add("--path_to_bowtie");
                args.add(new BowtieWrapper(getPipelineCtx().getLogger()).getExe().getParentFile().getPath());

                //args.add("--verbose");
                if (!indexOutputDir.exists())
                {
                    indexOutputDir.mkdirs();
                }
                //NOTE: this needs to be where the FASTA is located (probably the top level /Shared dir)
                args.add(indexOutputDir.getPath());

                getWrapper().execute(args);

                if (!genomeBuild.exists())
                {
                    throw new PipelineJobException("Unable to find file, expected: " + genomeBuild.getPath());
                }
            }

            output.appendOutputs(referenceGenome.getWorkingFastaFile(), genomeBuild, !(fastaParentDir.equals(outputDir)));

            //recache if not already
            AlignerIndexUtil.saveCachedIndex(hasCachedIndex, getPipelineCtx(), genomeBuild, CACHED_NAME, output);

            return output;
        }
    }

    public static class Provider extends AbstractAlignmentStepProvider<AlignmentStep>
    {
        public Provider()
        {
            super("Bismark", "Bismark is a tool to map bisulfite converted sequence reads and determine cytosine methylation states.  It will use bowtie for the alignment itself.", Arrays.asList(
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("-l"), "seed_length", "Seed Length", "The 'seed length'; i.e., the number of bases of the high quality end of the read to which the -n ceiling applies. The default is 28. Bowtie (and thus Bismark) is faster for larger values of -l. This option is only available for Bowtie 1 (for Bowtie 2 see -L).", "ldk-numberfield", null, 65),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("-n"), "max_seed_mismatches", "Max Seed Mismatches", "The maximum number of mismatches permitted in the 'seed', i.e. the first L base pairs of the read (where L is set with -l/--seedlen). This may be 0, 1, 2 or 3 and the default is 1. This option is only available for Bowtie 1 (for Bowtie 2 see -N).", "ldk-numberfield", null, 3),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("-e"), "maqerr", "Max Errors", "Maximum permitted total of quality values at all mismatched read positions throughout the entire alignment, not just in the 'seed'. The default is 70. Like Maq, bowtie rounds quality values to the nearest 10 and saturates at 30. This value is not relevant for Bowtie 2.", "ldk-numberfield", null, 240)
            ), null, "http://www.bioinformatics.babraham.ac.uk/projects/bismark/", true);
        }

        public BismarkAlignmentStep create(PipelineContext context)
        {
            return new BismarkAlignmentStep(this, context);
        }
    }

    public static class BismarkExtractorStep extends AbstractCommandPipelineStep<BismarkWrapper> implements BamProcessingStep
    {
        public BismarkExtractorStep(PipelineStepProvider provider, PipelineContext ctx)
        {
            super(provider, ctx, new BismarkWrapper(ctx.getLogger()));
        }

        @Override
        public Output processBam(ReadsetModel rs, File inputBam, ReferenceGenome referenceGenome, File outputDirectory) throws PipelineJobException
        {
            BamProcessingOutputImpl output = new BamProcessingOutputImpl();
            BismarkWrapper wrapper = getWrapper();

            List<String> args = new ArrayList<>();
            args.add(wrapper.getMethylationExtractorExe().getPath());
            args.add(inputBam.getPath());

            args.add("--samtools_path");
            args.add(new SamtoolsRunner(getPipelineCtx().getLogger()).getSamtoolsPath().getParentFile().getPath());

            //paired end vs. single
            if (rs.getFileName2() == null)
            {
                args.add("-s");
            }
            else
            {
                args.add("-p");
            }

            if (getClientCommandArgs() != null)
            {
                args.addAll(getClientCommandArgs());
            }

            args.add("-o");
            args.add(outputDirectory.getPath());

            getWrapper().setWorkingDir(outputDirectory);
            getWrapper().execute(args);

            //add outputs
            String outputBasename = FileUtil.getBaseName(inputBam);
            getWrapper().getLogger().debug("using basename: " + outputBasename);
            output.addOutput(new File(outputDirectory, outputBasename + ".M-bias.txt"), "Bismark M-Bias Report");

            File graph1 = new File(outputDirectory, outputBasename + ".M-bias_R1.png");
            if (graph1.exists())
            {
                output.addOutput(graph1, "Bismark M-Bias Image");
            }

            File graph2 = new File(outputDirectory, outputBasename + ".M-bias_R2.png");
            if (graph2.exists())
            {
                output.addOutput(graph2, "Bismark M-Bias Image");
            }

            output.addOutput(new File(outputDirectory, outputBasename + ".bam_splitting_report.txt"), "Bismark Splitting Report");

            //NOTE: because the data are likely directional, we will not encounter CTOB
            List<Pair<File, Integer>> CpGmethlyationData = Arrays.asList(
                    Pair.of(new File(outputDirectory, "CpG_OT_" + outputBasename + ".txt.gz"), 0),
                    Pair.of(new File(outputDirectory, "CpG_CTOT_" + outputBasename + ".txt.gz"), 0),
                    Pair.of(new File(outputDirectory, "CpG_OB_" + outputBasename + ".txt.gz"), -1),
                    Pair.of(new File(outputDirectory, "CpG_CTOB_" + outputBasename + ".txt.gz"), -1)
            );

            List<Pair<File, Integer>> NonCpGmethlyationData = Arrays.asList(
                    Pair.of(new File(outputDirectory, "NonCpG_OT_" + outputBasename + ".txt.gz"), 0),
                    Pair.of(new File(outputDirectory, "NonCpG_CTOT_" + outputBasename + ".txt.gz"), 0),
                    Pair.of(new File(outputDirectory, "NonCpG_OB_" + outputBasename + ".txt.gz"), -1),
                    Pair.of(new File(outputDirectory, "NonCpG_CTOB_" + outputBasename + ".txt.gz"), -1)
            );

            if (getProvider().getParameterByName("mbias_only").extractValue(getPipelineCtx().getJob(), getProvider(), Boolean.class))
            {
                getPipelineCtx().getJob().getLogger().info("mbias only was selected, no report will be created");
            }
            else if (getProvider().getParameterByName("siteReport").extractValue(getPipelineCtx().getJob(), getProvider(), Boolean.class))
            {
                getPipelineCtx().getLogger().info("creating per-site summary report");

                Integer minCoverageDepth = getProvider().getParameterByName("minCoverageDepth").extractValue(getPipelineCtx().getJob(), getProvider(), Integer.class);
                File siteReport = new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".CpG_Site_Summary.txt");
                File outputGff = new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".CpG_Site_Summary.gff");

                produceSiteReport(getWrapper().getLogger(), siteReport, outputGff, CpGmethlyationData, minCoverageDepth);
                if (siteReport.exists())
                {
                    output.addOutput(siteReport, "Bismark CpG Methylation Raw Data");

                    //also try to create an image summarizing:
                    File siteReportPng = createSummaryReport(getWrapper().getLogger(), siteReport, minCoverageDepth);
                    if (siteReportPng != null && siteReportPng.exists())
                    {
                        output.addOutput(siteReportPng, "Bismark CpG Methylation Report");
                        output.addSequenceOutput(siteReportPng, rs.getName() + " methylation report", "Bismark CpG Methylation Report", rs.getRowId(), null, referenceGenome.getGenomeId());
                    }
                }

                if (outputGff.exists())
                {
                    output.addOutput(outputGff, "Bismark CpG Methylation Rates");
                    output.addSequenceOutput(outputGff, rs.getName() + " methylation", "CpG Methylation Rates", rs.getRowId(), null, referenceGenome.getGenomeId());
                }

//                File siteReport2 = new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".NonCpG_Site_Summary.txt");
//                File outputGff2 = new File(outputDirectory, FileUtil.getBaseName(inputBam) + ".NonCpG_Site_Summary.gff");
//
//                produceSiteReport(getWrapper().getLogger(), siteReport2, outputGff2, NonCpGmethlyationData, minCoverageDepth);
//                if (siteReport2.exists())
//                {
//                    output.addOutput(siteReport2, "Bismark NonCpG Methylation Site Report");
//                }
//                if (outputGff2.exists())
//                {
//                    output.addOutput(outputGff2, "Bismark NonCpG Methylation Site Data");
//                    output.addSequenceOutput(outputGff2, rs.getName() + " methylation", "NonCpG Methylation Rate Data", rs);
//                }

                //NOTE: if we produce the summary report, assume these are discardable intermediates.  otherwise retain them
                for (Pair<File, Integer> pair : CpGmethlyationData)
                {
                    if (pair.first.exists())
                    {
                        output.addIntermediateFile(pair.first, "Bismark Methlyation Site Data");
                    }
                }

                for (Pair<File, Integer> pair : NonCpGmethlyationData)
                {
                    if (pair.first.exists())
                    {
                        output.addIntermediateFile(pair.first, "Bismark Methlyation Site Data");
                    }
                }
            }
            else
            {
                getPipelineCtx().getLogger().info("per-site report not selected, skipping");
                for (Pair<File, Integer> pair : CpGmethlyationData)
                {
                    if (pair.first.exists())
                    {
                        output.addOutput(pair.first, "Bismark Methlyation Site Data");
                    }
                }

                for (Pair<File, Integer> pair : NonCpGmethlyationData)
                {
                    if (pair.first.exists())
                    {
                        output.addOutput(pair.first, "Bismark Methlyation Site Data");
                    }
                }
            }

            return output;
        }

        private File createSummaryReport(Logger log, File siteReport, Integer minCoverageDepth)
        {
            try
            {
                String rScript = getScriptPath();

                List<String> params = new ArrayList<>();
                params.add(getRPath());
                params.add(rScript);
                params.add(siteReport.getPath());
                params.add(minCoverageDepth == null ? "0" : Integer.toString(minCoverageDepth));

                //depth cutoff.  hard-coded for now
                params.add("100");

                getWrapper().execute(params);

                File siteReportPng = new File(siteReport.getPath() + ".png");
                if (siteReportPng.exists())
                {
                    return siteReportPng;
                }
                else
                {
                    getPipelineCtx().getLogger().warn("unable to find output, expected: " + siteReportPng.getPath());
                }
            }
            catch (PipelineJobException e)
            {
                log.error("Error running R script.  This is probably an R configuration or library issue.  Skipping report", e);
            }

            return null;
        }

        private void produceSiteReport(Logger log, File output, File outputGff, List<Pair<File, Integer>> methlyationData, Integer minCoverageDepth) throws PipelineJobException
        {
            try
            {
                Set<String> keys = new TreeSet<>();

                Map<String, Integer[]> totalMethylatedPerSite = new HashMap<>();
                Map<String, Integer[]> totalNonMethylatedPerSite = new HashMap<>();
                if (minCoverageDepth == null)
                {
                    minCoverageDepth = 0;
                }

                for (Pair<File, Integer> pair : methlyationData)
                {
                    log.info("processing file: " + pair.first.getName());
                    if (!pair.first.exists())
                    {
                        log.warn("file does not exist, skipping: " + pair.first.getName());
                        continue;
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(pair.first)))))
                    {
                        String line;
                        String[] tokens;

                        int rowIdx = 0;
                        while ((line = reader.readLine()) != null)
                        {
                            rowIdx++;
                            if (rowIdx % 100000 == 0)
                            {
                                log.info("processed " + rowIdx + " lines");
                            }

                            if (rowIdx == 1 || StringUtils.trimToNull(line) == null)
                            {
                                log.debug("skipping line: " + rowIdx);
                                continue;
                            }

                            tokens = StringUtils.split(line, "\t");
                            if (tokens.length != 5)
                            {
                                log.error("incorrect number of items on line, expected 5: " + rowIdx);
                                log.error("[" + StringUtils.join(tokens, "],[") + "]");
                                continue;
                            }

                            Integer pos = ConvertHelper.convert(tokens[3], Integer.class);
                            pos += pair.second;
                            String key = tokens[2] + "||" + StringUtils.leftPad(Integer.toString(pos), 12, '0');
                            keys.add(key);

                            // NOTE: + indicates methylated, not the strand
                            Map<String, Integer[]> totalMap;
                            if ("+".equals(tokens[1]))
                            {
                                totalMap = totalMethylatedPerSite;
                            }
                            else if ("-".equals(tokens[1]))
                            {
                                totalMap = totalNonMethylatedPerSite;
                            }
                            else
                            {
                                log.error("unknown strand: " + tokens[1]);
                                continue;
                            }

                            //the array should be: total encountered, total plus strand, total minus strand,
                            Integer[] arr = totalMap.containsKey(key) ? totalMap.get(key) : new Integer[]{0, 0, 0};
                            arr[0]++;

                            //if the offset is zero, this is a proxy for being + strand.  otherwise it's minus strand
                            if (pair.second.equals(0))
                            {
                                arr[1]++;
                            }
                            else
                            {
                                arr[2]++;
                            }

                            totalMap.put(key, arr);
                        }
                    }
                }

                if (keys.isEmpty())
                {
                    log.info("no positions to report, skipping");
                    return;
                }

                //then write output
                if (!output.exists())
                {
                    log.debug("creating file: " + output.getPath());
                    output.createNewFile();
                }

                if (!outputGff.exists())
                {
                    log.debug("creating file: " + outputGff.getPath());
                    outputGff.createNewFile();
                }

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(output, true)); BufferedWriter gffWriter = new BufferedWriter(new FileWriter(outputGff, true)))
                {
                    log.info("writing output, " + keys.size() + " total positions");
                    DecimalFormat df = new DecimalFormat("0.00");

                    writer.write(StringUtils.join(new String[]{"Chr", "Pos", "Depth", "Methylation Rate", "Total Methylated", "Total NonMethylated", "Total Methylated Plus Strand", "Total Methylated Minus Strand", "Total NonMethylated Plus Strand", "Total NonMethylated Minus Strand"}, "\t") + '\n');
                    gffWriter.write("##gff-version 3" + System.getProperty("line.separator"));

                    String[] line;
                    for (String key : keys)
                    {
                        String[] tokens = key.split("\\|\\|");
                        line = new String[10];
                        line[0] = tokens[0];
                        line[1] = Integer.toString(Integer.parseInt(tokens[1]));  //pos, remove leading zeros

                        Integer[] methlyatedArr = totalMethylatedPerSite.containsKey(key) ? totalMethylatedPerSite.get(key) : new Integer[]{0, 0, 0};
                        Integer[] nonMethlyatedArr = totalNonMethylatedPerSite.containsKey(key) ? totalNonMethylatedPerSite.get(key) : new Integer[]{0, 0, 0};

                        int depth = methlyatedArr[0] + nonMethlyatedArr[0];
                        if (depth < minCoverageDepth)
                        {
                            continue;  //skip low coverage to save file size
                        }

                        line[2] = Integer.toString(depth);

                        Double rate = methlyatedArr[0].equals(0) ? 0.0 : ((double) methlyatedArr[0] / (double) depth);
                        line[3] = rate == null ? "" : df.format(rate);

                        line[4] = methlyatedArr[0].equals(0) ? "" : Integer.toString(methlyatedArr[0]);
                        line[5] = nonMethlyatedArr[0].equals(0) ? "" : Integer.toString(nonMethlyatedArr[0]);

                        line[6] = methlyatedArr[1].equals(0) ? "" : Integer.toString(methlyatedArr[1]);
                        line[7] = methlyatedArr[2].equals(0) ? "" : Integer.toString(methlyatedArr[2]);

                        line[8] = nonMethlyatedArr[1].equals(0) ? "" : Integer.toString(nonMethlyatedArr[1]);
                        line[9] = nonMethlyatedArr[2].equals(0) ? "" : Integer.toString(nonMethlyatedArr[2]);

                        writer.write(StringUtils.join(line, '\t') + System.getProperty("line.separator"));

                        String attributes = "Depth=" + depth + ";" +
                                "TotalMethlated:" + line[4] + ";" +
                                "TotalNonMethylated:" + line[5] + ";" +
                                "TotalMethylatedOnPlusStand:" + line[6] + ";" +
                                "TotalMethylatedOnMinusStand:" + line[7] + ";" +
                                "TotalNonMethylatedOnPlusStand:" + line[8] + ";" +
                                "TotalNonMethylatedOnPlusStand:" + line[9] + ";";

                        gffWriter.write(StringUtils.join(new String[]{
                                tokens[0],  //sequence name
                                ".",  //source
                                "site_methylation_rate",  //type
                                line[1],  //start, 1-based
                                line[1],  //end
                                (depth < minCoverageDepth ? "" : Double.toString(rate)),   //score (rate)
                                "+",       //strand
                                "0",       //phase
                                attributes
                        }, '\t') + System.getProperty("line.separator"));
                    }
                }
            }
            catch (Exception e)
            {
                throw new PipelineJobException(e);
            }
        }

        private String inferRPath()
        {
            String path;

            //preferentially use R config setup in scripting props.  only works if running locally.
            if (PipelineJobService.get().getLocationType() == PipelineJobService.LocationType.WebServer)
            {
                for (ExternalScriptEngineDefinition def : LabkeyScriptEngineManager.getEngineDefinitions())
                {
                    if (RScriptEngineFactory.isRScriptEngine(def.getExtensions()))
                    {
                        path = new File(def.getExePath()).getParent();
                        getPipelineCtx().getJob().getLogger().info("Using RSciptEngine path: " + path);
                        return path;
                    }
                }
            }

            //then pipeline config
            String packagePath = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath("R");
            if (StringUtils.trimToNull(packagePath) != null)
            {
                getPipelineCtx().getJob().getLogger().info("Using path from pipeline config: " + packagePath);
                return packagePath;
            }

            //then RHOME
            Map<String, String> env = System.getenv();
            if (env.containsKey("RHOME"))
            {
                getPipelineCtx().getJob().getLogger().info("Using path from RHOME: " + env.get("RHOME"));
                return env.get("RHOME");
            }

            //else assume it's in the PATH
            getPipelineCtx().getJob().getLogger().info("Unable to infer R path, using null");

            return null;
        }

        private String getRPath()
        {
            String exePath = "Rscript";

            //NOTE: this was added to better support team city agents, where R is not in the PATH, but RHOME is defined
            String packagePath = inferRPath();
            if (StringUtils.trimToNull(packagePath) != null)
            {
                exePath = (new File(packagePath, exePath)).getPath();
            }

            return exePath;
        }

        private String getScriptPath() throws PipelineJobException
        {
            Module module = ModuleLoader.getInstance().getModule(SequenceAnalysisModule.class);
            Resource script = module.getModuleResource("/external/R/methylationBasicStats.R");
            if (!script.exists())
                throw new PipelineJobException("Unable to find file: " + script.getPath());

            File f = ((FileResource) script).getFile();
            if (!f.exists())
                throw new PipelineJobException("Unable to find file: " + f.getPath());

            return f.getPath();
        }
    }

    public static class MethylationExtractorProvider extends AbstractPipelineStepProvider<BamProcessingStep>
    {
        public MethylationExtractorProvider()
        {
            super("BismarkMethylationExtractor", "Bismark Methylation Extractor", "Bismark Methylation Extractor", "This step runs the Bismark Methylation Extractor to determine cytosine methylation states.  This step will run against any BAM, but really only makes sense for BAMs created using Bismark upstream.", Arrays.asList(
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--merge_non_CpG"), "merge_non_CpG", "Merge non-CpG", "This will produce two output files (in --comprehensive mode) or eight strand-specific output files (default)", "checkbox", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true),
                    //always checked
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--gzip"), "gzip", "Compress Outputs", "If checked, the outputs will be compressed to save space", "hidden", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--report"), "report", "Produce Report", "Prints out a short methylation summary as well as the paramaters used to run this script.", "checkbox", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true),
                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--mbias_only"), "mbias_only", "M-bias Only", "The methylation extractor will read the entire file but only output the M-bias table and plots as well as a report (optional) and then quit.", "checkbox", null, false),
//                    ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--bedGraph"), "bedGraph", "Produce BED Graph", "After finishing the methylation extraction, the methylation output is written into a sorted bedGraph file that reports the position of a given cytosine and its methylation state (in %, see details below). The methylation extractor output is temporarily split up into temporary files, one per chromosome (written into the current directory or folder specified with -o/--output); these temp files are then used for sorting and deleted afterwards. By default, only cytosines in CpG context will be sorted. The option '--CX_context' may be used to report all cytosines irrespective of sequence context (this will take MUCH longer!). The default folder for temporary files during the sorting process is the output directory. The bedGraph conversion step is performed by the external module 'bismark2bedGraph'; this script needs to reside in the same folder as the bismark_methylation_extractor itself.", "checkbox", new JSONObject()
//                    {{
//                        put("checked", true);
//                    }}, true),
                    ToolParameterDescriptor.create("siteReport", "Produce Site Summary Report", "If selected, the raw methylation data will be processed to produce a simplified report showing rates per site, rather than per position in the genome.", "checkbox", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true),
                    ToolParameterDescriptor.create("minCoverageDepth", "Min Coverage Depth (For Site Report)", "If provided, only sites with at least this coverage depth will be included in the site-based rate calculation.", "ldk-integerfield", null, 10)
            ), null, "http://www.bioinformatics.babraham.ac.uk/projects/bismark/");
        }

        public BismarkExtractorStep create(PipelineContext context)
        {
            return new BismarkExtractorStep(this, context);
        }
    }

    protected File getExe()
    {
        return SequencePipelineService.get().getExeForPackage("BISMARKPATH", "bismark");
    }

    protected File getBuildExe()
    {
        return SequencePipelineService.get().getExeForPackage("BISMARKPATH", "bismark_genome_preparation");
    }

    protected File getMethylationExtractorExe()
    {
        return SequencePipelineService.get().getExeForPackage("BISMARKPATH", "bismark_methylation_extractor");
    }
}