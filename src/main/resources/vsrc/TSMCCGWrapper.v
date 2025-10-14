module TSMCCGWrapper(
  output out,
  input en,
  input test_en,
  input in
);
  CKLNQD1BWP16P90 RC_CGIC_INST(.E (en), .CP (in), .TE (test_en), .Q(out));
endmodule
