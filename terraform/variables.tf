variable "aws_region" {
  description = "AWS region where resources will be provisioned"
  type        = string
  default     = "us-east-1"
}

variable "bucket_name" {
  description = "Name of the S3 bucket used to store biometric reference images"
  type        = string
  default     = "plankton-liveness-biometrics"
}

variable "iam_user_name" {
  description = "Name of the IAM user that holds the local development credentials"
  type        = string
  default     = "plankton_liveness_user"
}
