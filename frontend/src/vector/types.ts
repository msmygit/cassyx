/**
 * Vector / SAI / ANN wire types (plan §6).
 *
 * Aliases over the GENERATED contract types in `src/api/schema.d.ts`, never re-declarations — so
 * a contract change breaks `tsc` here instead of drifting silently (see `src/api/types.ts`).
 */
import type { Schemas } from '../api/types';

export type SimilarityFunction = Schemas['SimilarityFunction'];
export type VectorColumn = Schemas['VectorColumn'];
export type VectorColumnDefinition = Schemas['VectorColumnDefinition'];
export type SaiIndex = Schemas['SaiIndex'];
export type SaiIndexDefinition = Schemas['SaiIndexDefinition'];
export type SaiIndexStatus = Schemas['SaiIndexStatus'];
export type AnnQueryRequest = Schemas['AnnQueryRequest'];
export type AnnQueryVectorSource = Schemas['AnnQueryVectorSource'];
export type AnnPredicate = Schemas['AnnPredicate'];
export type AnnQueryPreview = Schemas['AnnQueryPreview'];
export type SimilarityRequest = Schemas['SimilarityRequest'];
export type SimilarityResult = Schemas['SimilarityResult'];
export type DdlExecutionResult = Schemas['DdlExecutionResult'];
export type QueryResult = Schemas['QueryResult'];
export type ClusterCapabilities = Schemas['ClusterCapabilities'];
export type CapabilityName = Schemas['CapabilityName'];
export type CapabilitySupport = Schemas['CapabilitySupport'];

/** The three functions, in the order the UI offers them. `cosine` is Cassandra's default. */
export const SIMILARITY_FUNCTIONS: readonly SimilarityFunction[] = [
  'cosine',
  'dot_product',
  'euclidean',
] as const;

/** The predicate operators the contract enumerates. Nothing else may reach the API. */
export const PREDICATE_OPERATORS = [
  '=',
  '<',
  '<=',
  '>',
  '>=',
  'IN',
  'CONTAINS',
  'CONTAINS KEY',
  ':',
] as const;

export type PredicateOperator = (typeof PREDICATE_OPERATORS)[number];
