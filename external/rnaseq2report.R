#!/usr/bin/env Rscript
library(limma)
library(gmodels)
library(tidyr)
# library(circlize)
# library(enrichplot)
# library(pathview)
# library(DOSE)
# library(clusterProfiler)

# ------------------- Prepare -------------------
make_directories <- function(workdir) {
  dirs <- sapply(c("FunctionAnalysis", "DEGAnalysis", "GeneExpression", "QC"), function(subdir) {
    return(file.path(workdir, subdir))
  })

  sapply(dirs, function(dest) {
    dir.create(dest, showWarnings = FALSE)
  })
}

# ------------------- Main Analysis -------------------
get_rna_report_table <- function(df_exp, rt_group, gene_covert_table, main_dir) {

  pre_pca_data <- function(df_exp, rt_group) {
    # df_exp is the gene expression table of which the rownames is gene id and the colname is sample id
    # rt_group is the phenotype data of which the first column is the sample id and the second column is the group information

    rt_pca_d = df_exp[apply(df_exp, 1, function(x) { median(x) > 0 }),]
    data.a <- t(as.matrix(rt_pca_d))
    data.pca <- fast.prcomp(data.a, scale = T) # do PCA
    a <- summary(data.pca)
    tmp <- a$importance # a include 4 sections which contain importance
    pro1 <- as.numeric(sprintf("%.3f", tmp[2, 1])) * 100
    pro2 <- as.numeric(sprintf("%.3f", tmp[2, 2])) * 100
    pro3 <- as.numeric(sprintf("%.3f", tmp[2, 3])) * 100 # fetch the proportion of PC1, PC2 and PC3
    pc <- as.data.frame(a$x) # convert to data.frame

    if (is.null(rt_group)) {
      pc = pc
    } else {
      pc$group = rt_group$group
    }

    df_pc = data.frame(cbind(row.names(pc), pc), stringsAsFactors = FALSE)
    colnames(df_pc)[1] = 'SAMPLE_ID'
    return(df_pc)
  }

  limma_deg <- function(df_exp, rt_group) {
    # df_exp is the gene expression table of which the rownames is gene id and the colname is sample id
    # group_list is the vector contain sample group information
    group_list <- rt_group$group
    design <- model.matrix(~0 + factor(group_list))
    colnames(design) <- levels(factor(group_list))
    rownames(design) <- colnames(df_exp)

    # DEG
    # print(group_list)
    contrast_matrix <- makeContrasts(paste0(unique(rt_group$group), collapse = "-"), levels = design)

    ## step1
    fit <- lmFit(rt_exp_g, design)

    ## step2
    fit2 <- contrasts.fit(fit, contrast_matrix)
    fit2 <- eBayes(fit2) ## default no trend !!!

    ## eBayes() with trend = TRUE
    ## step3
    tempOutput <- topTable(fit2, coef = 1, n = Inf)

    # all fc
    tempOutput$PvalueLog <- -log10(tempOutput$P.Value)
    tempOutput$group <- 'non-deg'
    tempOutput$group[intersect(which(tempOutput$P.Value < 0.05),
                               which(abs(tempOutput$logFC) >= 1))] <- 'deg'
    all_gene_fc = data.frame(cbind(row.names(tempOutput), tempOutput), stringsAsFactors = FALSE)
    colnames(all_gene_fc)[1] = 'GENE_ID'

    # deg fc
    sig_fc <- tempOutput[intersect(which(abs(tempOutput$logFC) >= 1), which(tempOutput$P.Value < 0.05)),]
    df_sig_fc <- data.frame(cbind(row.names(sig_fc), sig_fc), stringsAsFactors = FALSE)
    colnames(df_sig_fc)[1] <- 'GENE_ID'

    sig_exp <- df_exp[row.names(df_sig_fc),]
    df_sig_exp <- data.frame(cbind(row.names(sig_exp), sig_exp), stringsAsFactors = FALSE)
    colnames(df_sig_exp)[1] <- 'GENE_ID'

    deg_mat_list <- list(all_gene_fc, df_sig_exp)
    return(deg_mat_list)
  }

  make_kegg_go <- function(gene_list_entrezid) {
    # go enrich
    ego_cc <- enrichGO(gene = gene_list_entrezid, OrgDb = org.Hs.eg.db,
                       ont = "CC", pAdjustMethod = "BH", pvalueCutoff = 0.05, qvalueCutoff = 0.1,
                       readable = TRUE)
    ego_bp <- enrichGO(gene = gene_list_entrezid, OrgDb = org.Hs.eg.db,
                       ont = "BP", pAdjustMethod = "fdr", pvalueCutoff = 0.05, qvalueCutoff = 0.1,
                       readable = TRUE)
    ego_mf <- enrichGO(gene = gene_list_entrezid, OrgDb = org.Hs.eg.db,
                       ont = "MF", pAdjustMethod = "BH", pvalueCutoff = 0.05, qvalueCutoff = 0.1,
                       readable = TRUE)

    df_ego_cc <- ego_cc@result
    df_ego_bp <- ego_bp@result
    df_ego_mf <- ego_mf@result

    # kegg enrich
    kk <- enrichKEGG(gene = gene_list_entrezid, organism = 'hsa', pvalueCutoff = 0.05)
    df_kk <- kk@result

    write.table(df_ego_cc, file = paste(main_dir, '/FunctionAnalysis/go_cc.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
    write.table(df_ego_bp, file = paste(main_dir, '/FunctionAnalysis/go_bp.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
    write.table(df_ego_mf, file = paste(main_dir, '/FunctionAnalysis/go_mf.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
    write.table(df_kk, file = paste(main_dir, '/FunctionAnalysis/kegg.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
  }


  if (is.null(rt_group)) {
    # basic output
    # expression table output
    df_exp_id <- data.frame(cbind(row.names(df_exp), df_exp), stringsAsFactors = FALSE)
    colnames(df_exp_id)[1] <- "Ensembl_ID"
    df_exp_mul_id <- merge(gene_covert_table, df_exp_id, by = "Ensembl_ID")
    write.table(df_exp_mul_id, file = paste(main_dir, '/GeneExpression/gene_expression_fpkm.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # box plot
    df_exp_d <- df_exp[apply(df_exp, 1, function(x) { median(x) > 0 }),]
    box_exp <- gather(df_exp_d)
    group_box <- rep(rt_group$group, each = dim(df_exp_d)[1])
    df_box <- data.frame(cbind(box_exp, group_box), stringsAsFactors = FALSE)
    colnames(df_box) <- c('sample_id', 'expression', 'group')
    df_box$expression <- as.numeric(df_box$expression)
    write.table(df_box, file = paste(main_dir, '/GeneExpression/all_gene_exp.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # hca
    rt_hclust <- hclust(d = dist(t(df_exp)), method = 'ward.D')
    rt_hclust_dend <- as.dendrogram(rt_hclust)
    new_order <- order.dendrogram(rt_hclust_dend)

    # pca
    mat_group <- data.frame(cbind(colnames(df_exp)[7:12], "group_list"), stringsAsFactors = FALSE)
    colnames(mat_group) <- c('samples_id', 'group')
    all_gene_pca <- pre_pca_data(df_exp, mat_group)
    write.table(all_gene_pca, file = paste(main_dir, '/GeneExpression/all_gene_pca.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
  } else {
    # basic output
    # expression table output
    df_exp_id <- data.frame(cbind(row.names(df_exp), df_exp), stringsAsFactors = FALSE)
    colnames(df_exp_id)[1] <- "Ensembl_ID"
    df_exp_mul_id <- merge(gene_covert_table, df_exp_id, by = "Ensembl_ID")

    write.table(df_exp_mul_id, file = paste(main_dir, '/GeneExpression/gene_expression_fpkm.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # box plot
    df_exp_d <- df_exp[apply(df_exp, 1, function(x) { median(x) > 0 }),]
    box_exp <- gather(df_exp_d)
    group_box <- rep(rt_group$group, each = dim(df_exp_d)[1])
    df_box <- data.frame(cbind(box_exp, group_box), stringsAsFactors = FALSE)
    colnames(df_box) <- c('sample_id', 'expression', 'group')
    df_box$expression <- as.numeric(df_box$expression)
    write.table(df_box, file = paste(main_dir, '/GeneExpression/all_gene_exp.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # hca
    rt_hclust <- hclust(d = dist(t(df_exp)), method = 'ward.D')
    rt_hclust_dend <- as.dendrogram(rt_hclust)
    new_order <- order.dendrogram(rt_hclust_dend)

    # pca
    all_gene_pca <- pre_pca_data(df_exp, rt_group)
    write.table(all_gene_pca, file = paste(main_dir, '/GeneExpression/all_gene_pca.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # prepare deg analysis data
    rt_mat_list <- limma_deg(df_exp, rt_group)
    rt_sig_fc <- rt_mat_list[[1]]

    rt_sig_exp <- rt_mat_list[[2]]
    write.table(rt_sig_fc, file = paste(main_dir, '/DEGAnalysis/deg_fc.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)
    write.table(rt_sig_exp, file = paste(main_dir, '/DEGAnalysis/deg_exp.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # deg pca
    rt_pc_deg <- pre_pca_data(rt_sig_exp[, -1], rt_group)
    write.table(rt_pc_deg, file = paste(main_dir, '/DEGAnalysis/deg_pca.txt', sep = ''),
                row.names = FALSE, col.names = TRUE, sep = '\t', quote = FALSE)

    # prepare kegg and go analysis data
    rt_sig_fc_com <- merge(rt_sig_fc, gene_covert_table, by = 1)
    gene_list_entrezid <- rt_sig_fc_com$GeneID
    # make_kegg_go(gene_list_entrezid)
  }
}

get_exe_path <- function() {
  initial.options <- commandArgs(trailingOnly = FALSE)
  file.arg.name <- "--file="
  script.name <- sub(file.arg.name, "", initial.options[grep(file.arg.name, initial.options)])
  script.dirname <- dirname(script.name)
  return(script.dirname)
}

args <- commandArgs(T)

if (length(args) < 3) {
  stop("At least four argument must be supplied (input file).", call. = FALSE)
} else {
  exp_table_file <- args[1]
  phenotype_file <- args[2]
  result_dir <- args[3]

  if (file_test('-f', exp_table_file) &&
      file_test('-f', phenotype_file) &&
      file_test('-d', result_dir)) {

    exe_path <- get_exe_path()
    anno_file <- file.path(exe_path, "references", "gene_id_convert_table_rnaseq_latest.txt")

    rt_exp_g <- read.csv(exp_table_file, sep = '\t', header = TRUE, row.names = 1)
    rt_group <- read.csv(phenotype_file, sep = '\t', header = TRUE)
    gene_covert_table <- read.csv(anno_file, sep = '\t', header = TRUE)

    # Prepare directories
    make_directories(result_dir)

    # Running
    get_rna_report_table(rt_exp_g, rt_group, gene_covert_table = gene_covert_table, main_dir = result_dir)
  } else {
    stop("Please check your arguments.", call. = FALSE)
  }
}