package org.labkey.sequenceanalysis.run.analysis;

import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.IOUtil;
import htsjdk.variant.utils.SAMSequenceDictionaryExtractor;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.sequenceanalysis.SequenceAnalysisModule;
import org.labkey.sequenceanalysis.util.SequenceUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MergeLoFreqVcfHandler extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private static final String MIN_AF = "minAfThreshold";
    private static final String MIN_COVERAGE = "minCoverage";

    public MergeLoFreqVcfHandler()
    {
        super(ModuleLoader.getInstance().getModule(SequenceAnalysisModule.class), "Merge LoFreq VCFs", "This will merge VCFs generate by LoFreq, considering the DepthOfCoverage data generated during those jobs.", null, Arrays.asList(
                ToolParameterDescriptor.create("basename", "Output Name", "The basename of the output file.", "textfield", new JSONObject(){{
                    put("allowBlank", false);
                }}, null),
                ToolParameterDescriptor.create(MIN_AF, "Min AF to Include", "A site will be included if there is a passing variant above this AF in at least one sample.", "ldk-numberfield", new JSONObject(){{
                    put("minValue", 0);
                    put("maxValue", 1);
                    put("decimalPrecision", 2);
                }}, 0.01),
                ToolParameterDescriptor.create(MIN_COVERAGE, "Min Coverage To Include", "A site will be reported as ND, unless coverage is above this threshold", "ldk-integerfield", new JSONObject(){{
                    put("minValue", 0);
                }}, 10)
            )
        );
    }

    @Override
    public boolean canProcess(SequenceOutputFile o)
    {
        return LofreqAnalysis.CATEGORY.equals(o.getCategory()) && o.getFile() != null && SequenceUtil.FILETYPE.vcf.getFileType().isType(o.getFile());
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
    }

    public static class Processor implements SequenceOutputHandler.SequenceOutputProcessor
    {
        @Override
        public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        private static final double NO_DATA_VAL = -1.0;

        private class SiteAndAlleles
        {
            private final String _contig;
            private final int _start;
            private final Allele _ref;

            Map<String, Map<Allele, Allele>> _encounteredAlleles = new HashMap<>();
            List<Allele> _alternates;

            public SiteAndAlleles(String contig, int start, Allele ref)
            {
                _contig = contig;
                _start = start;
                _ref = ref;
            }

            public Allele getRenamedAllele(VariantContext vc, Allele toCheck)
            {
                Allele ret = _encounteredAlleles.get(getEncounteredKey(vc.getStart(), vc.getReference())).get(toCheck);

                return ret == null ? toCheck : ret;
            }

            private String getEncounteredKey(int start, Allele ref)
            {
                return start + ":" + ref.getBaseString();
            }

            public void addSite(VariantContext vc)
            {
                Map<Allele, Allele> alleles = _encounteredAlleles.getOrDefault(getEncounteredKey(vc.getStart(), vc.getReference()), new HashMap<>());
                vc.getAlternateAlleles().forEach(a -> {
                    Allele translated = extractAlleleForPosition(vc, a);
                    if (translated != null)
                    {
                        if (!alleles.containsKey(a))
                            alleles.put(a, translated);

                        if (!_alternates.contains(translated))
                            _alternates.add(translated);
                    }
                });
                _encounteredAlleles.put(getEncounteredKey(vc.getStart(), vc.getReference()), alleles);
            }

            public boolean isMergedRef()
            {
                return _encounteredAlleles.size() > 1;
            }

            protected Allele extractAlleleForPosition(VariantContext vc, Allele a)
            {
                int offset = vc.getStart() - _start;

                //deletion
                if (a.length() <= offset)
                {
                    return Allele.SPAN_DEL;
                }
                else if (offset == 0 && a.length() == 1)
                {
                    return a;
                }
                else
                {
                    Allele ret = Allele.create(a.getBaseString().substring(offset, offset + 1));

                    return _ref.equals(ret, true) ? null: ret;
                }
            }
        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            String basename = ctx.getParams().getString("basename");
            basename = basename + (basename.endsWith(".") ? "" : ".");

            final double minAfThreshold = ctx.getParams().optDouble(MIN_AF, 0.0);
            final int minDepth = ctx.getParams().optInt(MIN_COVERAGE, 0);

            ctx.getLogger().info("Pass 1: Building whitelist of sites");
            Map<String, SiteAndAlleles> siteToAllele = new HashMap<>();
            List<Pair<String, Integer>> whitelistSites = new ArrayList<>();

            Set<Integer> genomeIds = new HashSet<>();

            for (SequenceOutputFile so : inputFiles)
            {
                if (so.getLibrary_id() == null)
                {
                    throw new PipelineJobException("VCF lacks library id: " + so.getRowid());
                }

                genomeIds.add(so.getLibrary_id());
                if (genomeIds.size() > 1)
                {
                    throw new PipelineJobException("Samples use more than one genome.  Genome IDs: " + StringUtils.join(genomeIds, ","));
                }

                try (VCFFileReader reader = new VCFFileReader(so.getFile()); CloseableIterator<VariantContext> it = reader.iterator())
                {
                    while (it.hasNext())
                    {
                        VariantContext vc = it.next();
                        if (vc.getAttribute("AF") == null)
                        {
                            continue;
                        }

                        double af = vc.getAttributeAsDouble("AF", 0.0);
                        if (af >= minAfThreshold)
                        {
                            for (int i = 0; i < vc.getLengthOnReference();i++)
                            {
                                int effectiveStart = vc.getStart() + i;
                                String key = getCacheKey(vc.getContig(), effectiveStart);
                                Allele ref = vc.getLengthOnReference() == 1 ? vc.getReference() : Allele.create(vc.getReference().getBaseString().substring(i, i + 1), true);
                                SiteAndAlleles site = siteToAllele.containsKey(key) ? siteToAllele.get(key) : new SiteAndAlleles(vc.getContig(), effectiveStart, ref);
                                if (!siteToAllele.containsKey(key))
                                {
                                    whitelistSites.add(Pair.of(vc.getContig(), effectiveStart));
                                }
                                siteToAllele.put(key, site);
                            }
                        }
                    }
                }
            }

            ctx.getLogger().info("total sites: " + whitelistSites.size());
            Collections.sort(whitelistSites);

            ctx.getLogger().info("Pass 2: establish alleles per site");
            for (SequenceOutputFile so : inputFiles)
            {
                try (VCFFileReader reader = new VCFFileReader(so.getFile()))
                {
                    for (Pair<String, Integer> site : whitelistSites)
                    {
                        String key = getCacheKey(site.getLeft(), site.getRight());

                        //NOTE: LoFreq should output one VCF line per allele
                        //NOTE: deletions spanning this site can also be included, with a start position ahead of this.
                        try (CloseableIterator<VariantContext> it = reader.query(site.getLeft(), site.getRight(), site.getRight()))
                        {
                            VariantContext vc = it.next();
                            if (vc.getAttribute("AF") == null)
                            {
                                continue;
                            }

                            //NOTE: the start position of this SiteAndAlleles might differ from the VC
                            siteToAllele.get(key).addSite(vc);
                        }
                    }
                }
            }

            ReferenceGenome genome = ctx.getSequenceSupport().getCachedGenome(genomeIds.iterator().next());
            SAMSequenceDictionary dict = SAMSequenceDictionaryExtractor.extractDictionary(genome.getSequenceDictionary().toPath());
            Map<String, Integer> contigToOffset = getContigToOffset(dict);

            ctx.getLogger().info("Building merged table");

            File output = new File(ctx.getOutputDir(), basename + "txt");
            int idx = 0;
            try (CSVWriter writer = new CSVWriter(IOUtil.openFileForBufferedUtf8Writing(output), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                writer.writeNext(new String[]{"OutputFileId", "ReadsetId", "Contig", "Start", "End", "Ref", "AltAlleles", "OrigRef", "OrigAlts", "Depth", "RefAF", "AltAFs"});

                for (Pair<String, Integer> site : whitelistSites)
                {
                    for (SequenceOutputFile so : inputFiles)
                    {
                        VCFFileReader reader = getReader(so.getFile());

                        //NOTE: LoFreq should output one VCF line per allele:
                        try (CloseableIterator<VariantContext> it = reader.query(site.getLeft(), site.getRight(), site.getRight()))
                        {
                            List<String> line = new ArrayList<>();
                            line.add(String.valueOf(so.getRowid()));
                            line.add(String.valueOf(so.getReadset()));

                            String key = getCacheKey(site.getLeft(), site.getRight());
                            SiteAndAlleles siteDef = siteToAllele.get(key);

                            line.add(site.getLeft());
                            line.add(String.valueOf(site.getRight()));
                            line.add(String.valueOf(site.getRight() + siteDef._ref.length() - 1));

                            line.add(siteDef._ref.getBaseString());
                            line.add(siteDef._alternates.stream().map(Allele::getBaseString).collect(Collectors.joining(";")));

                            if (!it.hasNext())
                            {
                                //No variant was called, so this is either considered all WT, or no-call
                                int depth = getReadDepth(so.getFile(), contigToOffset, site.getLeft(), site.getRight());
                                if (depth < minDepth)
                                {
                                    line.add("");
                                    line.add("");
                                    line.add(String.valueOf(depth));
                                    line.add("ND");
                                    line.add("ND");
                                }
                                else
                                {
                                    line.add("");
                                    line.add("");
                                    line.add(String.valueOf(depth));
                                    line.add("1");
                                    line.add(";0".repeat(siteDef._alternates.size()).substring(1));
                                }

                                writer.writeNext(line.toArray(new String[]{}));
                                idx++;
                            }
                            else
                            {
                                Integer depth = null;
                                Double totalAltAf = 0.0;
                                Map<Allele, Double> alleleToAf = new HashMap<>();
                                Set<Allele> refs = new HashSet<>();

                                while (it.hasNext())
                                {
                                    VariantContext vc = it.next();

                                    if (vc.getStart() > siteDef._start)
                                    {
                                        throw new PipelineJobException("Unexpected variant start.  site: " + siteDef._start + " / vc: " + vc.getStart() + " / " + so.getFile().getPath());
                                    }

                                    if (vc.getAttribute("GATK_DP") == null)
                                    {
                                        throw new PipelineJobException("Expected GATK_DP annotation on line " + key + " in file: " + so.getFile().getPath());
                                    }

                                    if (vc.getAttribute("AF") == null)
                                    {
                                        throw new PipelineJobException("Expected AF annotation on line " + key + " in file: " + so.getFile().getPath());
                                    }

                                    depth = vc.getAttributeAsInt("GATK_DP", 0);
                                    if (depth < minDepth)
                                    {
                                        vc.getAlternateAlleles().forEach(a -> {
                                            Allele translatedAllele = siteDef.getRenamedAllele(vc, a);
                                            if (translatedAllele != null)
                                            {
                                                double val = alleleToAf.getOrDefault(a, NO_DATA_VAL);
                                                alleleToAf.put(a, val);
                                            }
                                        });
                                    }
                                    else
                                    {
                                        List<Double> afs = vc.getAttributeAsDoubleList("AF", 0);
                                        if (afs.size() != vc.getAlternateAlleles().size())
                                        {
                                            throw new PipelineJobException("Expected AF annotation on line " + key + " in file: " + so.getFile().getPath() + " to be of length: " + vc.getAlternateAlleles().size());
                                        }

                                        totalAltAf += afs.stream().reduce(0.0, Double::sum);
                                        vc.getAlternateAlleles().forEach(a -> {
                                            int aIdx = vc.getAlternateAlleles().indexOf(a);

                                            a = siteDef.getRenamedAllele(vc, a);
                                            double val = alleleToAf.getOrDefault(a, 0.0);
                                            if (val == NO_DATA_VAL)
                                            {
                                                val = 0;
                                            }

                                            val = val + afs.get(aIdx);
                                            alleleToAf.put(a, val);
                                        });
                                    }
                                }

                                List<String> toWrite = new ArrayList<>(line);
                                if (!siteDef.isMergedRef())
                                {
                                    toWrite.add("");
                                    toWrite.add("");
                                }
                                else
                                {
                                    refs.remove(siteDef._ref);
                                    if (refs.isEmpty())
                                    {
                                        toWrite.add("");
                                        toWrite.add("");
                                    }
                                    else
                                    {
                                        toWrite.add(refs.stream().map(Allele::getBaseString).collect(Collectors.joining(";")));
                                        List<String> alleleSets = new ArrayList<>();
                                        refs.forEach(r -> {
                                            if (!siteDef._encounteredAlleles.containsKey(r))
                                            {
                                                throw new IllegalArgumentException("Reference not found: " + r.toString() + ", at site: " + siteDef._start + ", all refs: " + refs.stream().map(Allele::toString).collect(Collectors.joining(",")) + ", allowed: " + StringUtils.join(siteDef._encounteredAlleles.keySet(), ","));
                                            }

                                            alleleSets.add(StringUtils.join(siteDef._encounteredAlleles.get(r).keySet(), ","));
                                        });
                                        toWrite.add(alleleSets.stream().collect(Collectors.joining(";")));
                                    }
                                }

                                toWrite.add(String.valueOf(depth));
                                toWrite.add(String.valueOf(1 - totalAltAf));

                                //Add AFs in order:
                                List<Object> toAdd = new ArrayList<>();
                                for (Allele a : siteDef._alternates)
                                {
                                    double af = alleleToAf.getOrDefault(a, 0.0);
                                    toAdd.add(af == NO_DATA_VAL ? "ND" : af);
                                }
                                toWrite.add(toAdd.stream().map(String::valueOf).collect(Collectors.joining(";")));

                                writer.writeNext(toWrite.toArray(new String[]{}));
                                idx++;
                            }
                        }

                        if (idx % 500 == 0)
                        {
                            ctx.getLogger().info("Total sites written: " + idx);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            for (VCFFileReader reader : readerMap.values())
            {
                try
                {
                    reader.close();
                }
                catch (Throwable e)
                {
                    ctx.getLogger().error("Unable to close reader: " + e.getMessage());
                }
            }

            ctx.getFileManager().addSequenceOutput(output, "Merged LoFreq Variants: " + inputFiles.size() + " VCFs", "Merged LoFreq Variant Table", null, null, genome.getGenomeId(), null);
        }

        private String getCacheKey(String contig, int start)
        {
            return contig + "<>" + start;
        }

        private Map<String, Integer> getContigToOffset(SAMSequenceDictionary dict)
        {
            Map<String, Integer> ret = new HashMap<>();

            //Account for the header line:
            int offset = 1;
            for (SAMSequenceRecord rec : dict.getSequences())
            {
                ret.put(rec.getSequenceName(), offset);
                offset += rec.getSequenceLength();
            }

            return ret;
        }

        private int getReadDepth(File vcf, Map<String, Integer> contigToOffset, String contig, int position1) throws PipelineJobException
        {
            File gatkDepth = new File(vcf.getParentFile(), vcf.getName().replaceAll(".all.vcf.gz", ".coverage"));
            if (!gatkDepth.exists())
            {
                throw new PipelineJobException("File not found: " + gatkDepth.getPath());
            }

            try (Stream<String> lines = Files.lines(gatkDepth.toPath()))
            {
                int lineNo = contigToOffset.get(contig) + position1;
                String[] line = lines.skip(lineNo - 1).findFirst().get().split("\t");

                if (!line[0].equals(contig + ":" + position1))
                {
                    throw new PipelineJobException("Incorrect line at " + lineNo + ", expected " + contig + ":" + position1 + ", but was: " + line[0]);
                }

                return Integer.parseInt(line[1]);
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        private Map<File, VCFFileReader> readerMap = new HashMap<>();

        private VCFFileReader getReader(File f)
        {
            if (!readerMap.containsKey(f))
            {
                readerMap.put(f, new VCFFileReader(f));
            }

            return readerMap.get(f);
        }
    }

    //Adapted from GATK's GATKVariantContextUtils
    private static Allele determineReferenceAllele(final Allele ref1, final Allele ref2)
    {
        if ( ref1 == null || ref1.length() < ref2.length() )
        {
            return ref2;
        }
        else if ( ref2 == null || ref2.length() < ref1.length())
        {
            return ref1;
        }
        else if ( ref1.length() == ref2.length() && ! ref1.equals(ref2) )
        {
            throw new IllegalArgumentException(String.format("The provided reference alleles do not appear to represent the same position, %s vs. %s", ref1, ref2));
        }
        else
        {
            return ref1;
        }
    }

    private static Map<Allele, Allele> createAlleleMapping(final Allele refAllele, final Allele inputRef, final List<Allele> inputAlts)
    {
        if (refAllele.length() < inputRef.length())
        {
            throw new IllegalArgumentException("BUG: inputRef=" + inputRef + " is longer than refAllele=" + refAllele);
        }

        final byte[] extraBases = Arrays.copyOfRange(refAllele.getBases(), inputRef.length(), refAllele.length());

        final Map<Allele, Allele> map = new LinkedHashMap<>();
        for ( final Allele a : inputAlts )
        {
            if ( isNonSymbolicExtendableAllele(a) )
            {
                Allele extended = Allele.extend(a, extraBases);
                map.put(a, extended);
            } else if (a.equals(Allele.SPAN_DEL))
            {
                map.put(a, a);
            }
        }

        return map;
    }

    private static boolean isNonSymbolicExtendableAllele(final Allele allele)
    {
        return ! (allele.isReference() || allele.isSymbolic() || allele.equals(Allele.SPAN_DEL));
    }
}