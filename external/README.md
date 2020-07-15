# RNASEQ2REPORT

## Arguments
### exp_table_file
It is a merged gene expression table.

|GENE_ID|SAMPLE_XXX|SAMPLE_YYY|
|-------|----------|----------|
|ENSGXXX|1.23|23.11|

### phenotype_file
It is a phenotype table.

|sample_id|group|
|---------|-----|
|SAMPLE_ID|grp1 |

### result_dir
It is a destination directory.

## Command Example

```
rnaseq2report.R log2fpkm.txt phenotye.txt ./
```