terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# Uses the default AWS credentials provider chain (env vars, shared config/credentials,
# SSO, IAM role). No credentials are ever hardcoded here.
provider "aws" {
  region = var.aws_region
}

# ---------------------------------------------------------------------------
# S3 bucket: plankton-liveness-biometrics
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "biometrics" {
  bucket = var.bucket_name

  tags = {
    Project   = "plankton-liveness-service"
    ManagedBy = "terraform"
  }
}

# Native versioning enabled (RF05: re-validations overwrite reference.png keeping history)
resource "aws_s3_bucket_versioning" "biometrics" {
  bucket = aws_s3_bucket.biometrics.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Server-side encryption (SSE-S3 / AES256)
resource "aws_s3_bucket_server_side_encryption_configuration" "biometrics" {
  bucket = aws_s3_bucket.biometrics.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# Block all public access — biometric PII must never be publicly reachable
resource "aws_s3_bucket_public_access_block" "biometrics" {
  bucket = aws_s3_bucket.biometrics.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# IAM: minimum permissions for the local development credentials
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "liveness_access" {
  # S3: upload, read (used by Rekognition via S3 pointer) and existence check
  statement {
    sid    = "S3BiometricsAccess"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:HeadObject",
    ]

    resources = [
      "${aws_s3_bucket.biometrics.arn}/*",
    ]
  }

  # Rekognition: liveness (DetectFaces) and face match (CompareFaces)
  statement {
    sid    = "RekognitionAccess"
    effect = "Allow"

    actions = [
      "rekognition:DetectFaces",
      "rekognition:CompareFaces",
    ]

    resources = ["*"]
  }
}

resource "aws_iam_policy" "liveness_access" {
  name        = "plankton-liveness-access"
  description = "Minimum S3 and Rekognition permissions for plankton-liveness-service"
  policy      = data.aws_iam_policy_document.liveness_access.json
}

resource "aws_iam_user_policy_attachment" "liveness_access" {
  user       = var.iam_user_name
  policy_arn = aws_iam_policy.liveness_access.arn
}
